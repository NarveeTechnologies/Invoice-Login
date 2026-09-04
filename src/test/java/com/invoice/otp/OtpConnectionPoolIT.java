package com.invoice.otp;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.invoice.exception.MailDeliveryException;
import com.invoice.mail.EmailNotificationService;
import com.invoice.mail.MailFromProperties;
import com.invoice.mail.OtpEmailTemplate;
import com.invoice.mail.SmtpEmailProvider;
import com.invoice.mail.StalledSmtpServer;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.boot.autoconfigure.mail.MailProperties;
import com.invoice.config.MailConfig;

/**
 * A slow mail server must not exhaust the database connection pool.
 *
 * <p>This is the test for the defect that made the OTP send transactional
 * around the SMTP call. It was invisible to every other kind of test: the code
 * was correct, the transaction was correct, and a mocked mail sender returns
 * instantly so the connection was never held long enough to notice.
 *
 * <p>The arrangement mirrors production at small scale — a Hikari pool of 3 and
 * a relay that accepts connections and then stalls. With the send inside the
 * transaction, three concurrent requests hold all three connections for the
 * duration of the SMTP timeout and a fourth, unrelated database query cannot
 * get a connection at all. That fourth query is the assertion: it stands in for
 * every other request the service is meant to be serving while the mail
 * provider is unwell.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OtpConnectionPoolIT {

	private static final int POOL_SIZE = 3;
	private static final int CONCURRENT_SENDS = 6;
	private static final int SMTP_TIMEOUT_MS = 3000;

	@Container
	@SuppressWarnings("resource")
	static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:16-alpine")
					.withDatabaseName("invoice")
					.withUsername("invoice")
					.withPassword("invoice");

	private HikariDataSource dataSource;
	private OtpService service;
	private StalledSmtpServer stalledRelay;

	@BeforeAll
	void setUp() throws Exception {
		POSTGRES.start();

		HikariConfig hikari = new HikariConfig();
		hikari.setJdbcUrl(POSTGRES.getJdbcUrl());
		hikari.setUsername(POSTGRES.getUsername());
		hikari.setPassword(POSTGRES.getPassword());
		hikari.setMaximumPoolSize(POOL_SIZE);
		// Short, so a starved caller fails quickly instead of the test hanging.
		hikari.setConnectionTimeout(2000);
		dataSource = new HikariDataSource(hikari);

		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("CREATE SCHEMA IF NOT EXISTS invoice");
		jdbc.execute("SET search_path TO invoice");
		Flyway.configure().dataSource(dataSource)
				.schemas("invoice").defaultSchema("invoice")
				.table("flyway_schema_history_login")
				.baselineOnMigrate(true).baselineVersion("0")
				.locations("classpath:db/migration")
				.load().migrate();

		stalledRelay = new StalledSmtpServer();

		MailProperties mailProperties = new MailProperties();
		mailProperties.setHost("127.0.0.1");
		mailProperties.setPort(stalledRelay.port());
		mailProperties.getProperties().put("mail.smtp.auth", "false");
		mailProperties.getProperties().put("mail.smtp.ssl.enable", "false");
		mailProperties.getProperties().put("mail.smtp.starttls.enable", "false");
		mailProperties.getProperties().put("mail.smtp.connectiontimeout",
				String.valueOf(SMTP_TIMEOUT_MS));
		mailProperties.getProperties().put("mail.smtp.timeout", String.valueOf(SMTP_TIMEOUT_MS));
		mailProperties.getProperties().put("mail.smtp.writetimeout", String.valueOf(SMTP_TIMEOUT_MS));

		MailFromProperties from = new MailFromProperties();
		from.setAddress("no-reply@example.com");
		from.setName("Invoice");

		OtpProperties otpProperties = new OtpProperties();
		otpProperties.setPepper("pool-test-pepper-at-least-32-bytes-long!!");
		// Ceilings out of the way; this test is about connections, not limits.
		otpProperties.setMaxPerIdentifierPerHour(1000);
		otpProperties.setMaxPerIpPerHour(1000);
		otpProperties.setResendCooldown(java.time.Duration.ZERO);

		OtpChallengeRepository repository = new OtpChallengeRepository(jdbc);
		OtpHasher hasher = new OtpHasher(otpProperties);

		service = new OtpService(repository, new OtpCodeGenerator(), hasher,
				new OtpRateLimiter(repository, otpProperties),
				new OtpAuditLogger(org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class)),
				new EmailNotificationService(
						new SmtpEmailProvider(new MailConfig(mailProperties).javaMailSender(), from),
						new OtpEmailTemplate()),
				otpProperties,
				new DataSourceTransactionManager(dataSource));
	}

	@Test
	@DisplayName("a stalled mail relay does not starve the connection pool")
	void stalledRelayDoesNotStarveThePool() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_SENDS);
		CountDownLatch allSending = new CountDownLatch(CONCURRENT_SENDS);
		AtomicInteger deliveryFailures = new AtomicInteger();

		List<Callable<Void>> sends = new ArrayList<>();
		for (int i = 0; i < CONCURRENT_SENDS; i++) {
			int n = i;
			sends.add(() -> {
				try {
					service.request("pool" + n + "@example.com", OtpPurpose.REGISTRATION,
							identifier -> false, new OtpRequestContext("203.0.113." + n, "junit"));
				} catch (MailDeliveryException expected) {
					deliveryFailures.incrementAndGet();
				} finally {
					allSending.countDown();
				}
				return null;
			});
		}

		List<Future<Void>> running = new ArrayList<>();
		for (Callable<Void> send : sends) {
			running.add(pool.submit(send));
		}

		try {
			// While all six are stuck talking to a relay that never answers,
			// an ordinary query must still get a connection. This is the whole
			// assertion: with the send inside the transaction it could not.
			Thread.sleep(600);

			long startedAt = System.nanoTime();
			try (Connection connection = dataSource.getConnection()) {
				assertNotNull(connection, "no connection available during a mail stall");
				try (var statement = connection.prepareStatement("SELECT 1")) {
					assertTrue(statement.executeQuery().next());
				}
			} catch (Exception e) {
				fail("the connection pool was starved while SMTP was stalled: " + e.getMessage()
						+ " — this is the Hikari exhaustion the transaction restructure fixes");
			}
			long waitedMs = (System.nanoTime() - startedAt) / 1_000_000;

			assertTrue(waitedMs < 1500,
					"waited " + waitedMs + "ms for a connection during a mail stall; "
							+ "connections are being held across SMTP");

			for (Future<Void> f : running) {
				f.get(SMTP_TIMEOUT_MS * 5L, TimeUnit.MILLISECONDS);
			}
		} finally {
			pool.shutdownNow();
		}

		assertEquals(CONCURRENT_SENDS, deliveryFailures.get(),
				"every send should have surfaced as a delivery failure");
	}

	@Test
	@DisplayName("a challenge nobody received is retired, not left valid")
	void undeliveredChallengeIsRetired() {
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		jdbc.update("DELETE FROM invoice.otp_challenges");

		assertThrows(MailDeliveryException.class, () -> service.request(
				"undelivered@example.com", OtpPurpose.REGISTRATION,
				identifier -> false, new OtpRequestContext("203.0.113.9", "junit")));

		// The row survives, because the rate limiter counts it and an operator
		// needs to see the attempt — but it is not spendable.
		Integer total = jdbc.queryForObject(
				"SELECT count(*) FROM invoice.otp_challenges", Integer.class);
		assertEquals(1, total, "the attempt must remain visible to the rate limiter");

		String reason = jdbc.queryForObject(
				"SELECT invalidated_reason FROM invoice.otp_challenges", String.class);
		assertEquals(OtpInvalidationReason.DELIVERY_FAILED.name(), reason,
				"an undelivered passcode was left valid");
	}
}
