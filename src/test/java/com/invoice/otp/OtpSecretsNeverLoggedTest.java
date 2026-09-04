package com.invoice.otp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.slf4j.LoggerFactory;

import com.invoice.exception.MailDeliveryException;
import com.invoice.mail.EmailMessage;
import com.invoice.mail.EmailNotificationService;
import com.invoice.mail.LoggingEmailProvider;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Nothing that authenticates a user may reach a log file.
 *
 * <p>Logs travel further than the database does — to aggregators, to support
 * tickets, into screenshots. A passcode in a log line is a passcode anyone with
 * read access to logging can use, for as long as it is valid.
 *
 * <p>Captures the whole {@code com.invoice} logger tree at TRACE and asserts on
 * every line produced, so a future {@code log.debug("otp={}", code)} anywhere in
 * these paths fails the build.
 */
class OtpSecretsNeverLoggedTest {

	static PlatformTransactionManager noopTransactions() {
		PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
		when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
		return tm;
	}


	private static final String EMAIL = "user@example.com";
	private static final String PEPPER = "secret-pepper-value-at-least-32-bytes-xx";
	private static final OtpRequestContext CONTEXT =
			new OtpRequestContext("203.0.113.7", "junit");

	private ListAppender<ILoggingEvent> appender;
	private Logger root;

	@BeforeEach
	void attachAppender() {
		appender = new ListAppender<>();
		appender.start();
		root = (Logger) LoggerFactory.getLogger("com.invoice");
		root.setLevel(Level.TRACE);
		root.addAppender(appender);
	}

	@AfterEach
	void detachAppender() {
		root.detachAppender(appender);
	}

	private String allOutput() {
		return appender.list.stream()
				.map(e -> e.getFormattedMessage() + " "
						+ (e.getThrowableProxy() != null ? e.getThrowableProxy().getMessage() : ""))
				.reduce("", (a, b) -> a + "\n" + b);
	}

	private OtpService serviceCapturing(List<String> mailedCodes,
			EmailNotificationService mail) {
		OtpProperties properties = new OtpProperties();
		properties.setPepper(PEPPER);
		OtpHasher hasher = new OtpHasher(properties);
		OtpChallengeRepository repository = mock(OtpChallengeRepository.class);
		when(repository.insert(anyString(), any(), anyString(), any(), anyInt(), any(), any(),
				any(), anyBoolean(), any())).thenReturn(1L);
		OtpRateLimiter limiter = mock(OtpRateLimiter.class);
		when(limiter.check(anyString(), any(), any(), any()))
				.thenReturn(OtpRateLimitDecision.allow());

		doAnswer(inv -> {
			mailedCodes.add(inv.getArgument(2));
			return null;
		}).when(mail).sendOtp(anyString(), any(), anyString(), any(), any());

		return new OtpService(repository, new OtpCodeGenerator(), hasher, limiter,
				new OtpAuditLogger(mock(org.springframework.jdbc.core.JdbcTemplate.class)),
				mail, properties, noopTransactions());
	}

	@Test
	@DisplayName("the passcode never appears in any log line on the send path")
	void codeNotLoggedOnSend() {
		java.util.ArrayList<String> mailed = new java.util.ArrayList<>();
		EmailNotificationService mail = mock(EmailNotificationService.class);
		when(mail.providerName()).thenReturn("smtp");

		serviceCapturing(mailed, mail)
				.request(EMAIL, OtpPurpose.LOGIN, identifier -> true, CONTEXT);

		assertEquals(1, mailed.size(), "a code should have been mailed");
		String code = mailed.get(0);
		assertFalse(allOutput().contains(code),
				"the passcode " + code + " appeared in a log line");
	}

	@Test
	@DisplayName("the passcode never appears in a log line when delivery fails")
	void codeNotLoggedOnDeliveryFailure() {
		java.util.ArrayList<String> mailed = new java.util.ArrayList<>();
		EmailNotificationService mail = mock(EmailNotificationService.class);
		when(mail.providerName()).thenReturn("smtp");
		OtpService service = serviceCapturing(mailed, mail);
		doAnswer(inv -> {
			mailed.add(inv.getArgument(2));
			throw new MailDeliveryException("could not send",
					new RuntimeException("SMTP 535 auth failed for relay.example:465"));
		}).when(mail).sendOtp(anyString(), any(), anyString(), any(), any());

		assertThrows(MailDeliveryException.class,
				() -> service.request(EMAIL, OtpPurpose.LOGIN, identifier -> true, CONTEXT));

		assertFalse(allOutput().contains(mailed.get(0)),
				"the passcode leaked on the failure path");
	}

	@Test
	@DisplayName("the pepper never appears in any log line")
	void pepperNotLogged() {
		java.util.ArrayList<String> mailed = new java.util.ArrayList<>();
		EmailNotificationService mail = mock(EmailNotificationService.class);
		when(mail.providerName()).thenReturn("smtp");
		serviceCapturing(mailed, mail)
				.request(EMAIL, OtpPurpose.LOGIN, identifier -> true, CONTEXT);

		assertFalse(allOutput().contains(PEPPER), "the HMAC pepper leaked into the logs");
	}

	@Test
	@DisplayName("a wrong guess does not log the submitted code or the stored hash")
	void verifyDoesNotLogCandidates() {
		OtpProperties properties = new OtpProperties();
		properties.setPepper(PEPPER);
		OtpHasher hasher = new OtpHasher(properties);
		OtpChallengeRepository repository = mock(OtpChallengeRepository.class);
		String storedHash = hasher.hashCode(OtpPurpose.LOGIN, EMAIL, "ABC234");
		when(repository.lockLatest(anyString(), eq(OtpPurpose.LOGIN)))
				.thenReturn(Optional.of(new OtpChallenge(1L, hasher.hashIdentifier(EMAIL),
						OtpPurpose.LOGIN, storedHash, Instant.now().plusSeconds(600), null,
						0, 5, null, null, Instant.now(), UUID.randomUUID(), true)));
		when(repository.recordFailedAttempt(anyLong(), any())).thenReturn(4);
		OtpRateLimiter limiter = mock(OtpRateLimiter.class);

		OtpService service = new OtpService(repository, new OtpCodeGenerator(), hasher, limiter,
				new OtpAuditLogger(mock(org.springframework.jdbc.core.JdbcTemplate.class)),
				mock(EmailNotificationService.class), properties, noopTransactions());

		service.verify(EMAIL, OtpPurpose.LOGIN, "ZZZ999", CONTEXT);

		String output = allOutput();
		assertAll(
				() -> assertFalse(output.contains("ZZZ999"), "the submitted guess was logged"),
				() -> assertFalse(output.contains("ABC234"), "the real code was logged"),
				() -> assertFalse(output.contains(storedHash), "the stored hash was logged"));
	}

	@Test
	@DisplayName("the development provider logs metadata but never the message body")
	void loggingProviderDoesNotEmitTheBody() {
		// This provider exists to make local work possible. If it printed the
		// body it would print the passcode, and the safe-by-default story would
		// be false exactly where it is relied on.
		new LoggingEmailProvider().send(new EmailMessage(
				EMAIL, "Your Invoice sign-in code",
				"Use this code to sign in to Invoice:\n\n    ABC234\n",
				"<html><body>ABC234</body></html>"));

		String output = allOutput();
		assertAll(
				() -> assertFalse(output.contains("ABC234"),
						"the logging provider printed the passcode"),
				() -> assertTrue(output.contains(EMAIL),
						"it should still record who the message was for"));
	}
}
