// Same package as the class under test: UserServiceImpl's constructor is
// package-private, and widening production visibility purely for a test is the
// wrong trade.
package com.invoice.serviceImpl;

import com.invoice.config.MailConfig;
import com.invoice.entity.User;
import com.invoice.exception.MailDeliveryException;
import com.invoice.repository.AdminRepository;
import com.invoice.repository.TokenRepository;
import com.invoice.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.mail.internet.MimeMessage;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A one-time passcode that cannot be delivered must not be reported as sent.
 *
 * All three OTP paths previously caught the mail exception, logged it, and
 * returned normally — so the API answered 200 "OTP sent successfully" while the
 * user waited for a message that was never going to arrive. Nothing in the
 * response distinguished a working mail server from a broken one.
 */
class OtpMailFailureTest {

    private static final String EMAIL = "user@example.com";

    private UserRepository userRepository;
    private TokenRepository tokenRepository;
    private JavaMailSender javaMailSender;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        tokenRepository = mock(TokenRepository.class);
        javaMailSender = mock(JavaMailSender.class);

        service = new UserServiceImpl(mock(MailConfig.class), mock(AdminRepository.class),
                mock(EntityManager.class));
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "tokenRepository", tokenRepository);
        ReflectionTestUtils.setField(service, "javaMailSender", javaMailSender);
        ReflectionTestUtils.setField(service, "fromEmail", "no-reply@example.com");

        User user = new User();
        user.setEmail(EMAIL);
        user.setFullName("Test User");
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
        when(javaMailSender.createMimeMessage())
                .thenReturn(mock(MimeMessage.class, withSettings().lenient()));
    }

    @Test
    @DisplayName("a mail failure propagates instead of being reported as success")
    void mailFailurePropagates() {
        doThrow(new MailSendException("SMTP 535 auth failed for smtp.internal.example:465"))
                .when(javaMailSender).send(any(MimeMessage.class));

        assertThrows(MailDeliveryException.class, () -> service.sendOtp(EMAIL),
                "the caller must learn the passcode was not delivered");
    }

    @Test
    @DisplayName("the user-facing message leaks no mail host, account or driver detail")
    void messageDoesNotLeakInfrastructure() {
        doThrow(new MailSendException("SMTP 535 auth failed for smtp.internal.example:465"))
                .when(javaMailSender).send(any(MimeMessage.class));

        MailDeliveryException e =
                assertThrows(MailDeliveryException.class, () -> service.sendOtp(EMAIL));

        String msg = e.getMessage();
        assertAll(
                () -> assertFalse(msg.contains("smtp"), "must not name the mail host"),
                () -> assertFalse(msg.contains("535"), "must not echo the SMTP status"),
                () -> assertFalse(msg.toLowerCase().contains("auth failed"),
                        "must not echo the driver error"),
                () -> assertFalse(msg.contains("465"), "must not name the port"));
    }

    @Test
    @DisplayName("the original cause is preserved for the server log")
    void causeIsPreservedForDiagnosis() {
        MailSendException cause =
                new MailSendException("SMTP 535 auth failed for smtp.internal.example:465");
        doThrow(cause).when(javaMailSender).send(any(MimeMessage.class));

        MailDeliveryException e =
                assertThrows(MailDeliveryException.class, () -> service.sendOtp(EMAIL));
        assertSame(cause, e.getCause(),
                "the message is sanitised for the user, so the cause must survive for the log");
    }

    @Test
    @DisplayName("a successful send still returns normally and persists the passcode")
    void successPathUnchanged() {
        assertDoesNotThrow(() -> service.sendOtp(EMAIL));
        verify(tokenRepository).save(any());
        verify(javaMailSender).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("an unregistered address still fails with the existing message, not a mail error")
    void unregisteredAddressUnchanged() {
        when(userRepository.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.sendOtp("nobody@example.com"));
        assertTrue(e.getMessage().contains("not registered"),
                "the frontend keys its 'Invalid Email' branch off this wording");
        assertFalse(e instanceof MailDeliveryException);
    }
}
