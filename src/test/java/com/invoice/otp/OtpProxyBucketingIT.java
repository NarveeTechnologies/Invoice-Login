package com.invoice.otp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The consequence of getting {@code OTP_TRUSTED_PROXY_COUNT} wrong, measured
 * against the real rate limiter rather than asserted about the parser.
 *
 * <p>{@link ClientIpResolverTest} already covers which string comes out. What it
 * cannot show is the thing that actually matters in production: whether distinct
 * users end up in distinct rate-limit buckets. That only appears once the
 * resolved address has been hashed, written, and counted — so this test carries
 * it all the way through to {@link OtpRateLimiter}.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OtpProxyBucketingIT {

	@Container
	@SuppressWarnings("resource")
	static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:16-alpine")
					.withDatabaseName("invoice")
					.withUsername("invoice")
					.withPassword("invoice");

	private JdbcTemplate jdbc;
	private OtpChallengeRepository repository;
	private OtpHasher hasher;
	private OtpProperties properties;

	@BeforeEach
	void setUp() {
		POSTGRES.start();
		DriverManagerDataSource ds = new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		ds.setDriverClassName("org.postgresql.Driver");
		jdbc = new JdbcTemplate(ds);
		jdbc.execute("CREATE SCHEMA IF NOT EXISTS invoice");
		Flyway.configure().dataSource(ds)
				.schemas("invoice").defaultSchema("invoice")
				.table("flyway_schema_history_login")
				.baselineOnMigrate(true).baselineVersion("0")
				.locations("classpath:db/migration").load().migrate();
		jdbc.execute("SET search_path TO invoice");
		jdbc.update("DELETE FROM invoice.otp_challenges");

		properties = new OtpProperties();
		properties.setPepper("proxy-bucketing-pepper-at-least-32-bytes");
		properties.setMaxPerIpPerHour(3);
		properties.setMaxPerIdentifierPerHour(1000);
		properties.setResendCooldown(Duration.ZERO);

		hasher = new OtpHasher(properties);
		repository = new OtpChallengeRepository(jdbc);
	}

	/** A request as it reaches Invoice-Login behind nginx and the gateway. */
	private HttpServletRequest requestFrom(String clientIp, String forgedPrefix) {
		HttpServletRequest r = mock(HttpServletRequest.class);
		// The peer is always the gateway container — identical for every user.
		org.mockito.Mockito.when(r.getRemoteAddr()).thenReturn("172.18.0.9");
		String chain = (forgedPrefix == null ? "" : forgedPrefix + ", ")
				+ clientIp + ", 172.18.0.2";
		org.mockito.Mockito.when(r.getHeader("X-Forwarded-For")).thenReturn(chain);
		org.mockito.Mockito.when(r.getHeader("User-Agent")).thenReturn("junit");
		return r;
	}

	/** Issues one challenge for the given request context, as OtpService would. */
	private void issue(String identifier, OtpRequestContext context) {
		repository.insert(hasher.hashIdentifier(identifier), OtpPurpose.LOGIN,
				hasher.hashCode(OtpPurpose.LOGIN, identifier, "ABC234"),
				Instant.now().plusSeconds(600), 5,
				hasher.hashOpaque(context.ipAddress()), hasher.hashOpaque(context.userAgent()),
				java.util.UUID.randomUUID(), true, Instant.now());
	}

	private boolean allowed(String identifier, OtpRequestContext context) {
		return new OtpRateLimiter(repository, properties)
				.check(hasher.hashIdentifier(identifier),
						hasher.hashOpaque(context.ipAddress()), OtpPurpose.LOGIN, Instant.now())
				.allowed();
	}

	@Test
	@DisplayName("count=0: every user behind the gateway lands in ONE bucket")
	void misconfiguredCountCollapsesAllUsersIntoOneBucket() {
		// This is the failure mode of leaving the default in a proxied
		// deployment. It does not look like a bug: it looks like the rate
		// limiter working, until unrelated users start being refused.
		ClientIpResolver resolver = new ClientIpResolver(0);

		for (int i = 0; i < 3; i++) {
			issue("user" + i + "@example.com",
					resolver.contextOf(requestFrom("203.0.113." + i, null)));
		}

		// A fourth, entirely unrelated user is now refused, having made no
		// requests of their own.
		assertFalse(allowed("victim@example.com",
						resolver.contextOf(requestFrom("203.0.113.99", null))),
				"an unrelated user should have been throttled by three strangers' requests");
	}

	@Test
	@DisplayName("count=2: distinct clients get distinct buckets")
	void correctCountSeparatesUsers() {
		ClientIpResolver resolver = new ClientIpResolver(2);

		for (int i = 0; i < 3; i++) {
			issue("user" + i + "@example.com",
					resolver.contextOf(requestFrom("203.0.113." + i, null)));
		}

		assertTrue(allowed("victim@example.com",
						resolver.contextOf(requestFrom("203.0.113.99", null))),
				"a different client IP must have its own allowance");
	}

	@Test
	@DisplayName("count=2: one client's own ceiling still applies")
	void correctCountStillEnforcesTheCeiling() {
		ClientIpResolver resolver = new ClientIpResolver(2);
		OtpRequestContext sameClient = resolver.contextOf(requestFrom("203.0.113.7", null));

		for (int i = 0; i < 3; i++) {
			assertTrue(allowed("user" + i + "@example.com", sameClient));
			issue("user" + i + "@example.com", sameClient);
		}
		assertFalse(allowed("user4@example.com", sameClient),
				"the per-IP ceiling must still bite for a single client");
	}

	@Test
	@DisplayName("count=2: a forged X-Forwarded-For prefix cannot buy a fresh bucket")
	void forgedPrefixCannotEvadeTheCeiling() {
		// The attack the naive fix enables: send your own X-Forwarded-For and
		// rotate it. Counting from the right ignores it entirely.
        ClientIpResolver resolver = new ClientIpResolver(2);

		for (int i = 0; i < 3; i++) {
			issue("attacker@example.com",
					resolver.contextOf(requestFrom("203.0.113.7", "9.9.9." + i)));
		}

		assertFalse(allowed("attacker@example.com",
						resolver.contextOf(requestFrom("203.0.113.7", "9.9.9.250"))),
				"rotating the forged prefix bought a fresh bucket — the ceiling is evadable");
	}
}
