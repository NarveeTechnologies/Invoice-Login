package com.invoice.otp;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The OTP guarantees that live in the database rather than in Java.
 *
 * <p>Runs against a real PostgreSQL because the properties under test are
 * properties of the engine: {@code SELECT ... FOR UPDATE} serialising two
 * concurrent verifications, a conditional {@code UPDATE} refusing a second
 * consume, and the CHECK constraints holding when application code is wrong.
 * An in-memory database would let all three pass while none of them worked.
 *
 * <p>The schema is created by running the real Flyway migration, so this also
 * covers the fresh-bootstrap path required for deployment.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OtpRepositoryIT {

	@Container
	@SuppressWarnings("resource")
	static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:16-alpine")
					.withDatabaseName("invoice")
					.withUsername("invoice")
					.withPassword("invoice");

	private static DataSource dataSource;
	private static JdbcTemplate jdbc;
	private OtpChallengeRepository repository;
	private OtpHasher hasher;

	@BeforeAll
	void migrate() {
		POSTGRES.start();
		DriverManagerDataSource ds = new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		ds.setDriverClassName("org.postgresql.Driver");
		dataSource = ds;
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("CREATE SCHEMA IF NOT EXISTS invoice");

		// The real migration, with the real settings. baseline-version 0 is the
		// setting that stops V001 being skipped on a non-empty schema.
		Flyway.configure()
				.dataSource(dataSource)
				.schemas("invoice")
				.defaultSchema("invoice")
				.table("flyway_schema_history_login")
				.baselineOnMigrate(true)
				.baselineVersion("0")
				.locations("classpath:db/migration")
				.load()
				.migrate();
	}

	@BeforeEach
	void setUp() {
		jdbc.execute("SET search_path TO invoice");
		jdbc.update("DELETE FROM invoice.otp_challenges");
		OtpProperties props = new OtpProperties();
		props.setPepper("integration-test-pepper-at-least-32-bytes");
		hasher = new OtpHasher(props);
		repository = new OtpChallengeRepository(jdbc);
	}

	private long insertChallenge(String identifier, OtpPurpose purpose, String code,
			Instant expiresAt, int maxAttempts) {
		return repository.insert(
				hasher.hashIdentifier(identifier), purpose,
				hasher.hashCode(purpose, identifier, code),
				expiresAt, maxAttempts, hasher.hashOpaque("203.0.113.7"),
				hasher.hashOpaque("junit"), UUID.randomUUID(), true, Instant.now());
	}

	@Test
	@DisplayName("the migration creates the table and nothing stores a plaintext code")
	void migrationApplied() {
		Integer tables = jdbc.queryForObject("""
				SELECT count(*) FROM information_schema.tables
				 WHERE table_schema = 'invoice' AND table_name = 'otp_challenges'
				""", Integer.class);
		assertEquals(1, tables, "V001 did not apply — check spring.flyway.baseline-version");

		insertChallenge("user@example.com", OtpPurpose.LOGIN, "ABC234",
				Instant.now().plusSeconds(600), 5);

		// No column anywhere holds the code or the address.
		Integer leaks = jdbc.queryForObject("""
				SELECT count(*) FROM invoice.otp_challenges
				 WHERE code_hash LIKE '%ABC234%'
				    OR identifier_hash LIKE '%user@example.com%'
				""", Integer.class);
		assertEquals(0, leaks, "a plaintext passcode or address reached the table");
	}

	@Test
	@DisplayName("the legacy plaintext otp table is gone")
	void legacyTableDropped() {
		Integer legacy = jdbc.queryForObject("""
				SELECT count(*) FROM information_schema.tables
				 WHERE table_schema = 'invoice' AND table_name = 'otp'
				""", Integer.class);
		assertEquals(0, legacy, "the legacy plaintext otp table still exists");
	}

	@Test
	@DisplayName("two simultaneous verifications of the same code produce exactly one success")
	void concurrentConsumeYieldsOneWinner() throws Exception {
		long id = insertChallenge("race@example.com", OtpPurpose.LOGIN, "ABC234",
				Instant.now().plusSeconds(600), 5);

		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CyclicBarrier startTogether = new CyclicBarrier(threads);
		AtomicInteger successes = new AtomicInteger();

		try {
			Callable<Void> attempt = () -> {
				startTogether.await(10, TimeUnit.SECONDS);
				// Each thread gets its own connection and its own transaction,
				// which is what makes this a real race rather than a sequence.
				try (var conn = dataSource.getConnection()) {
					conn.setAutoCommit(false);
					try (var lock = conn.prepareStatement("""
							SELECT consumed_at FROM invoice.otp_challenges
							 WHERE id = ? FOR UPDATE
							""")) {
						lock.setLong(1, id);
						try (var rs = lock.executeQuery()) {
							rs.next();
							if (rs.getTimestamp("consumed_at") != null) {
								conn.rollback();
								return null;
							}
						}
					}
					try (var consume = conn.prepareStatement("""
							UPDATE invoice.otp_challenges SET consumed_at = now()
							 WHERE id = ? AND consumed_at IS NULL
							""")) {
						consume.setLong(1, id);
						if (consume.executeUpdate() == 1) {
							successes.incrementAndGet();
						}
					}
					conn.commit();
				}
				return null;
			};

			for (Future<Void> f : pool.invokeAll(java.util.Collections.nCopies(threads, attempt))) {
				f.get(30, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}

		assertEquals(1, successes.get(),
				"a one-time passcode was spent " + successes.get() + " times");
	}

	@Test
	@DisplayName("a spent challenge cannot be spent again")
	void replayRefused() {
		long id = insertChallenge("replay@example.com", OtpPurpose.LOGIN, "ABC234",
				Instant.now().plusSeconds(600), 5);
		assertTrue(repository.consume(id, Instant.now()), "first consume should win");
		assertFalse(repository.consume(id, Instant.now()), "replay must be refused");
	}

	@Test
	@DisplayName("a challenge is found only under the purpose it was issued for")
	void purposeIsolation() {
		insertChallenge("purpose@example.com", OtpPurpose.ACCOUNT_NUMBER_CHANGE, "ABC234",
				Instant.now().plusSeconds(600), 5);

		String idHash = hasher.hashIdentifier("purpose@example.com");
		assertTrue(repository.lockLatest(idHash, OtpPurpose.ACCOUNT_NUMBER_CHANGE).isPresent());
		assertTrue(repository.lockLatest(idHash, OtpPurpose.LOGIN).isEmpty(),
				"a bank-change code was visible to the login flow");
	}

	@Test
	@DisplayName("a resend retires the previous code without deleting the history")
	void resendSupersedes() {
		String idHash = hasher.hashIdentifier("resend@example.com");
		insertChallenge("resend@example.com", OtpPurpose.LOGIN, "AAA222",
				Instant.now().plusSeconds(600), 5);

		int retired = repository.invalidateLive(
				idHash, OtpPurpose.LOGIN, OtpInvalidationReason.SUPERSEDED, Instant.now());
		assertEquals(1, retired);

		insertChallenge("resend@example.com", OtpPurpose.LOGIN, "BBB333",
				Instant.now().plusSeconds(600), 5);

		// Both rows survive: the rate limiter counts them.
		Integer rows = jdbc.queryForObject(
				"SELECT count(*) FROM invoice.otp_challenges WHERE identifier_hash = ?",
				Integer.class, idHash);
		assertEquals(2, rows, "a resend deleted history the rate limiter needs");
		assertEquals(2, repository.countByIdentifierSince(idHash, Instant.now().minusSeconds(60)));

		OtpChallenge latest = repository.lockLatest(idHash, OtpPurpose.LOGIN).orElseThrow();
		assertNull(latest.invalidatedAt(), "the newest challenge must still be live");
	}

	@Test
	@DisplayName("the attempt ceiling burns the challenge and stays inside the CHECK constraint")
	void attemptCeiling() {
		long id = insertChallenge("attempts@example.com", OtpPurpose.LOGIN, "ABC234",
				Instant.now().plusSeconds(600), 3);

		assertEquals(2, repository.recordFailedAttempt(id, Instant.now()));
		assertEquals(1, repository.recordFailedAttempt(id, Instant.now()));
		assertEquals(0, repository.recordFailedAttempt(id, Instant.now()));
		// One past the ceiling: must not throw, and must not go negative.
		assertEquals(0, repository.recordFailedAttempt(id, Instant.now()));

		OtpChallenge burned = repository.lockLatest(
				hasher.hashIdentifier("attempts@example.com"), OtpPurpose.LOGIN).orElseThrow();
		assertNotNull(burned.invalidatedAt(), "the challenge should have been retired");
		assertEquals(OtpInvalidationReason.EXHAUSTED.name(), burned.invalidatedReason());
		assertFalse(burned.isLive(Instant.now()));
	}

	@Test
	@DisplayName("an expired challenge is not live")
	void expiry() {
		insertChallenge("expired@example.com", OtpPurpose.LOGIN, "ABC234",
				Instant.now().minusSeconds(1), 5);
		OtpChallenge c = repository.lockLatest(
				hasher.hashIdentifier("expired@example.com"), OtpPurpose.LOGIN).orElseThrow();
		assertTrue(c.isExpired(Instant.now()));
		assertFalse(c.isLive(Instant.now()));
	}

	@Test
	@DisplayName("expiry is exact at the boundary")
	void expiryBoundary() {
		Instant expiresAt = Instant.now().plusSeconds(300);
		insertChallenge("boundary@example.com", OtpPurpose.LOGIN, "ABC234", expiresAt, 5);
		OtpChallenge c = repository.lockLatest(
				hasher.hashIdentifier("boundary@example.com"), OtpPurpose.LOGIN).orElseThrow();

		assertAll(
				() -> assertFalse(c.isExpired(expiresAt.minusMillis(1)), "one ms before: live"),
				// The comparison is `!now.isBefore(expiresAt)`, so the expiry
				// instant itself is already expired. Stated explicitly because
				// an off-by-one here is a passcode that outlives its window.
				() -> assertTrue(c.isExpired(expiresAt), "exactly at expiry: expired"),
				() -> assertTrue(c.isExpired(expiresAt.plusMillis(1)), "one ms after: expired"),
				() -> assertTrue(c.isLive(expiresAt.minusMillis(1))),
				() -> assertFalse(c.isLive(expiresAt)));
	}

	@Test
	@DisplayName("concurrent wrong guesses cannot exceed the attempt ceiling")
	void concurrentAttemptsCannotBypassTheCeiling() throws Exception {
		// The ceiling is enforced by a conditional UPDATE plus the table's CHECK
		// constraint. Without the `attempt_count < max_attempts` predicate,
		// simultaneous guesses would each read the same count and each
		// increment past it — or violate the constraint and error.
		int maxAttempts = 3;
		long id = insertChallenge("concurrent-attempts@example.com", OtpPurpose.LOGIN,
				"ABC234", Instant.now().plusSeconds(600), maxAttempts);

		int threads = 10;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CyclicBarrier startTogether = new CyclicBarrier(threads);
		AtomicInteger errors = new AtomicInteger();

		try {
			Callable<Void> guess = () -> {
				startTogether.await(10, TimeUnit.SECONDS);
				try {
					repository.recordFailedAttempt(id, Instant.now());
				} catch (RuntimeException e) {
					errors.incrementAndGet();
				}
				return null;
			};
			for (Future<Void> f : pool.invokeAll(java.util.Collections.nCopies(threads, guess))) {
				f.get(30, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}

		assertEquals(0, errors.get(), "the CHECK constraint was violated under concurrency");

		Integer finalCount = jdbc.queryForObject(
				"SELECT attempt_count FROM invoice.otp_challenges WHERE id = ?", Integer.class, id);
		assertEquals(maxAttempts, finalCount,
				"attempt_count reached " + finalCount + " against a ceiling of " + maxAttempts);

		OtpChallenge burned = repository.lockLatest(
				hasher.hashIdentifier("concurrent-attempts@example.com"),
				OtpPurpose.LOGIN).orElseThrow();
		assertFalse(burned.isLive(Instant.now()), "the challenge should be burned");
	}

	@Test
	@DisplayName("rate-limit counters window correctly by identifier and by IP")
	void rateLimitCounters() {
		String idHash = hasher.hashIdentifier("counts@example.com");
		for (int i = 0; i < 3; i++) {
			insertChallenge("counts@example.com", OtpPurpose.LOGIN, "ABC234",
					Instant.now().plusSeconds(600), 5);
		}
		assertEquals(3, repository.countByIdentifierSince(idHash, Instant.now().minusSeconds(3600)));
		assertEquals(0, repository.countByIdentifierSince(idHash, Instant.now().plusSeconds(60)),
				"a future window must count nothing");
		assertEquals(3, repository.countByIpSince(
				hasher.hashOpaque("203.0.113.7"), Instant.now().minusSeconds(3600)));
		assertEquals(0, repository.countByIpSince(null, Instant.now().minusSeconds(3600)),
				"a null ip must not match rows");
	}

	@Test
	@DisplayName("retention removes only rows older than the cutoff, whatever their state")
	void retentionSweep() {
		long spent = insertChallenge("old@example.com", OtpPurpose.LOGIN, "ABC234",
				Instant.now().plusSeconds(600), 5);
		repository.consume(spent, Instant.now());
		insertChallenge("new@example.com", OtpPurpose.LOGIN, "ABC234",
				Instant.now().plusSeconds(600), 5);

		// Age the spent row past the cutoff.
		jdbc.update("UPDATE invoice.otp_challenges SET created_at = now() - interval '40 days' "
				+ "WHERE id = ?", spent);

		assertEquals(1, repository.deleteOlderThan(Instant.now().minus(Duration.ofDays(30))));
		Integer left = jdbc.queryForObject(
				"SELECT count(*) FROM invoice.otp_challenges", Integer.class);
		assertEquals(1, left, "the recent row must survive");
	}

	@Test
	@DisplayName("lastIssuedAt drives the resend cooldown")
	void lastIssuedAt() {
		String idHash = hasher.hashIdentifier("cooldown@example.com");
		assertTrue(repository.lastIssuedAt(idHash, OtpPurpose.LOGIN).isEmpty());
		insertChallenge("cooldown@example.com", OtpPurpose.LOGIN, "ABC234",
				Instant.now().plusSeconds(600), 5);
		Optional<Instant> last = repository.lastIssuedAt(idHash, OtpPurpose.LOGIN);
		assertTrue(last.isPresent());
		assertTrue(Duration.between(last.get(), Instant.now()).abs().toSeconds() < 30);
	}
}
