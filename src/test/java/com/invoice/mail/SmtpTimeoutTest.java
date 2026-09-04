package com.invoice.mail;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.mail.MailProperties;

import com.invoice.config.MailConfig;
import com.invoice.exception.MailDeliveryException;
import com.invoice.otp.OtpPurpose;

/**
 * The SMTP timeouts must actually reach JavaMail.
 *
 * <p>They did not. {@code application.properties} declared
 * connection/read/write timeouts all along, but {@code MailConfig} built its own
 * {@code Properties} map from a hand-written list of three keys — auth,
 * ssl.enable, starttls.enable — so every other {@code spring.mail.properties.*}
 * entry was discarded. JavaMail's default is to wait indefinitely.
 *
 * <p>Asserting on configuration values would not have caught that: the values
 * were present and correct in the file. The only test that catches it is one
 * that makes a real connection to a server that never answers and measures how
 * long the client waits.
 */
class SmtpTimeoutTest {

	private static MailConfig configuredFor(int port, int connectMs, int readMs) {
		MailProperties properties = new MailProperties();
		properties.setHost("127.0.0.1");
		properties.setPort(port);
		properties.getProperties().put("mail.smtp.auth", "false");
		properties.getProperties().put("mail.smtp.ssl.enable", "false");
		properties.getProperties().put("mail.smtp.starttls.enable", "false");
		properties.getProperties().put("mail.smtp.connectiontimeout", String.valueOf(connectMs));
		properties.getProperties().put("mail.smtp.timeout", String.valueOf(readMs));
		properties.getProperties().put("mail.smtp.writetimeout", String.valueOf(readMs));
		return new MailConfig(properties);
	}

	private static SmtpEmailProvider providerFor(MailConfig config) {
		MailFromProperties from = new MailFromProperties();
		from.setAddress("no-reply@example.com");
		from.setName("Invoice");
		return new SmtpEmailProvider(config.javaMailSender(), from);
	}

	@Test
	@DisplayName("every configured spring.mail.properties entry reaches the mail session")
	void everyPropertyReachesTheSession() {
		// The regression guard for the hand-written three-key map.
		var sender = (org.springframework.mail.javamail.JavaMailSenderImpl)
				configuredFor(2525, 1000, 2000).javaMailSender();
		var session = sender.getJavaMailProperties();

		assertAll(
				() -> assertEquals("1000", session.getProperty("mail.smtp.connectiontimeout")),
				() -> assertEquals("2000", session.getProperty("mail.smtp.timeout")),
				() -> assertEquals("2000", session.getProperty("mail.smtp.writetimeout")),
				() -> assertEquals("false", session.getProperty("mail.smtp.auth")));
	}

	@Test
	@DisplayName("the smtps timeouts are set too, for implicit TLS on 465")
	void smtpsTimeoutsAreSet() {
		// Production uses implicit TLS on 465, where JavaMail consults the
		// mail.smtps.* names. Setting only mail.smtp.* would leave production
		// unbounded while UAT — which is plaintext — looked correct.
		var sender = (org.springframework.mail.javamail.JavaMailSenderImpl)
				configuredFor(2525, 1000, 2000).javaMailSender();
		var session = sender.getJavaMailProperties();

		assertAll(
				() -> assertEquals("1000", session.getProperty("mail.smtps.connectiontimeout")),
				() -> assertEquals("2000", session.getProperty("mail.smtps.timeout")),
				() -> assertEquals("2000", session.getProperty("mail.smtps.writetimeout")));
	}

	@Test
	@DisplayName("timeouts default to bounded values when configuration sets none")
	void timeoutsAreBoundedByDefault() {
		// No configuration at all must still not produce an unbounded sender.
		MailProperties bare = new MailProperties();
		bare.setHost("127.0.0.1");
		bare.setPort(2525);
		var sender = (org.springframework.mail.javamail.JavaMailSenderImpl)
				new MailConfig(bare).javaMailSender();
		var session = sender.getJavaMailProperties();

		assertAll(
				() -> assertNotNull(session.getProperty("mail.smtp.connectiontimeout")),
				() -> assertNotNull(session.getProperty("mail.smtp.timeout")),
				() -> assertNotNull(session.getProperty("mail.smtp.writetimeout")));
	}

	@Test
	@DisplayName("a stalled relay fails within the read timeout instead of hanging")
	void stalledRelayFailsWithinTheReadTimeout() throws Exception {
		try (StalledSmtpServer stalled = new StalledSmtpServer()) {
			int readTimeoutMs = 1500;
			SmtpEmailProvider provider =
					providerFor(configuredFor(stalled.port(), 1000, readTimeoutMs));

			long startedAt = System.nanoTime();
			assertThrows(MailDeliveryException.class, () -> provider.send(new EmailMessage(
					"user@example.com", "Your Invoice sign-in code",
					"plain body", "<p>html body</p>")));
			long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

			assertTrue(stalled.connectionsAccepted() >= 1,
					"the test server should have accepted a connection — otherwise this "
							+ "measured a connect failure, not a stall");
			// Generous ceiling: the point is that it is bounded at all, not the
			// exact figure. Unbounded means this never returns.
			assertTrue(elapsedMs < readTimeoutMs * 6L,
					"took " + elapsedMs + "ms against a " + readTimeoutMs
							+ "ms read timeout — the timeout is not reaching JavaMail");
		}
	}

	@Test
	@DisplayName("a stalled relay surfaces as MailDeliveryException, never as success")
	void stalledRelayIsNotReportedAsSuccess() throws Exception {
		try (StalledSmtpServer stalled = new StalledSmtpServer()) {
			SmtpEmailProvider provider = providerFor(configuredFor(stalled.port(), 800, 800));
			EmailNotificationService mail =
					new EmailNotificationService(provider, new OtpEmailTemplate());

			// The OTP path must learn the passcode was not delivered.
			assertThrows(MailDeliveryException.class, () -> mail.sendOtp(
					"user@example.com", OtpPurpose.LOGIN, "ABC234",
					Duration.ofMinutes(10), UUID.randomUUID()));
		}
	}

	@Test
	@DisplayName("the timeout message leaks no host, port or driver detail")
	void timeoutMessageLeaksNothing() throws Exception {
		try (StalledSmtpServer stalled = new StalledSmtpServer()) {
			SmtpEmailProvider provider = providerFor(configuredFor(stalled.port(), 800, 800));

			MailDeliveryException e = assertThrows(MailDeliveryException.class,
					() -> provider.send(new EmailMessage("user@example.com", "subject",
							"plain", "<p>html</p>")));

			String message = e.getMessage();
			assertAll(
					() -> assertFalse(message.contains(String.valueOf(stalled.port()))),
					() -> assertFalse(message.contains("127.0.0.1")),
					() -> assertFalse(message.toLowerCase().contains("timeout")),
					() -> assertNotNull(e.getCause(), "the cause must survive for the log"));
		}
	}
}
