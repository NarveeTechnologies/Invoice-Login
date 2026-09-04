package com.invoice.otp;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Data access for {@code otp_challenges}.
 *
 * <p>Deliberately JdbcTemplate rather than a JPA entity, for two reasons.
 *
 * <p>The first is schema authority. Invoice-Login runs
 * {@code spring.jpa.hibernate.ddl-auto=update} against the {@code invoice}
 * schema that Invoice-Service also builds, and that arrangement has already
 * produced four tables whose live column types are an artefact of container
 * boot order rather than of either service's design. Mapping this table as an
 * entity would enrol the one table whose shape is a security control into that
 * same race. With no entity, Hibernate never sees it and the Flyway migration
 * is its only author.
 *
 * <p>The second is that verification needs {@code SELECT ... FOR UPDATE} and
 * single-statement conditional updates. Those are the mechanism that makes
 * concurrent verification safe, and expressing them through JPA would mean
 * fighting the persistence context for control of the exact SQL.
 */
@Repository
public class OtpChallengeRepository {

	private final JdbcTemplate jdbc;

	public OtpChallengeRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	private static final String COLUMNS = """
			id, identifier_hash, purpose, code_hash, expires_at, consumed_at,
			attempt_count, max_attempts, invalidated_at, invalidated_reason,
			created_at, correlation_id, account_exists
			""";

	private static final RowMapper<OtpChallenge> MAPPER = (rs, rowNum) -> new OtpChallenge(
			rs.getLong("id"),
			rs.getString("identifier_hash"),
			OtpPurpose.valueOf(rs.getString("purpose")),
			rs.getString("code_hash"),
			instant(rs.getTimestamp("expires_at")),
			instant(rs.getTimestamp("consumed_at")),
			rs.getInt("attempt_count"),
			rs.getInt("max_attempts"),
			instant(rs.getTimestamp("invalidated_at")),
			rs.getString("invalidated_reason"),
			instant(rs.getTimestamp("created_at")),
			UUID.fromString(rs.getString("correlation_id")),
			rs.getBoolean("account_exists"));

	private static Instant instant(Timestamp ts) {
		return ts == null ? null : ts.toInstant();
	}

	/**
	 * Retires every live challenge for this identifier and purpose. Called
	 * before a new one is issued, so that a resend genuinely invalidates its
	 * predecessor rather than leaving two valid codes in flight.
	 *
	 * <p>Rows are marked, not deleted. The legacy implementation called
	 * {@code deleteByEmail} here, which meant an unauthenticated request could
	 * erase the evidence of its own previous requests — and with it any hope of
	 * rate limiting on history.
	 *
	 * @return how many challenges were retired
	 */
	public int invalidateLive(String identifierHash, OtpPurpose purpose,
			OtpInvalidationReason reason, Instant now) {
		return jdbc.update("""
				UPDATE otp_challenges
				   SET invalidated_at = ?, invalidated_reason = ?, updated_at = ?
				 WHERE identifier_hash = ?
				   AND purpose = ?
				   AND consumed_at IS NULL
				   AND invalidated_at IS NULL
				""",
				Timestamp.from(now), reason.name(), Timestamp.from(now),
				identifierHash, purpose.name());
	}

	/**
	 * Retires one challenge by id.
	 *
	 * <p>Used when delivery fails after the challenge has already been
	 * committed. The insert and the send are deliberately not in one
	 * transaction any more (see {@link OtpService#request}), so a failed send
	 * can no longer be undone by a rollback — it is undone by this, in its own
	 * short transaction.
	 *
	 * @return true if this call retired it
	 */
	public boolean invalidateById(long id, OtpInvalidationReason reason, Instant now) {
		return jdbc.update("""
				UPDATE otp_challenges
				   SET invalidated_at = ?, invalidated_reason = ?, updated_at = ?
				 WHERE id = ?
				   AND consumed_at IS NULL
				   AND invalidated_at IS NULL
				""", Timestamp.from(now), reason.name(), Timestamp.from(now), id) == 1;
	}

	/** @return the id of the inserted challenge */
	public long insert(String identifierHash, OtpPurpose purpose, String codeHash,
			Instant expiresAt, int maxAttempts, String ipHash, String userAgentHash,
			UUID correlationId, boolean accountExists, Instant now) {
		return jdbc.queryForObject("""
				INSERT INTO otp_challenges
				    (identifier_hash, purpose, code_hash, expires_at, max_attempts,
				     ip_hash, user_agent_hash, correlation_id, account_exists,
				     created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?::uuid, ?, ?, ?)
				RETURNING id
				""",
				Long.class,
				identifierHash, purpose.name(), codeHash, Timestamp.from(expiresAt), maxAttempts,
				ipHash, userAgentHash, correlationId.toString(), accountExists,
				Timestamp.from(now), Timestamp.from(now));
	}

	/**
	 * Locks and returns the newest challenge for this identifier and purpose.
	 *
	 * <p>{@code FOR UPDATE} is what serialises concurrent verification. Two
	 * requests arriving with the same code both reach this statement; one takes
	 * the row lock and the other blocks until the first has committed its
	 * {@code consumed_at}, at which point it re-reads the row and sees it spent.
	 * Without the lock both would read {@code consumed_at IS NULL}, both would
	 * match the hash, and both would succeed.
	 *
	 * <p>Must be called inside a transaction, which is the caller's contract.
	 */
	public Optional<OtpChallenge> lockLatest(String identifierHash, OtpPurpose purpose) {
		List<OtpChallenge> found = jdbc.query("""
				SELECT %s
				  FROM otp_challenges
				 WHERE identifier_hash = ?
				   AND purpose = ?
				 ORDER BY created_at DESC, id DESC
				 LIMIT 1
				   FOR UPDATE
				""".formatted(COLUMNS),
				MAPPER, identifierHash, purpose.name());
		return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
	}

	/**
	 * Marks a challenge spent, but only if it is still unspent.
	 *
	 * <p>The {@code consumed_at IS NULL} predicate is the replay guard. Even if
	 * the row lock were somehow not held, a second consume would update zero
	 * rows and the caller would reject the attempt.
	 *
	 * @return true if this call is the one that spent it
	 */
	public boolean consume(long id, Instant now) {
		return jdbc.update("""
				UPDATE otp_challenges
				   SET consumed_at = ?, updated_at = ?
				 WHERE id = ?
				   AND consumed_at IS NULL
				   AND invalidated_at IS NULL
				""", Timestamp.from(now), Timestamp.from(now), id) == 1;
	}

	/**
	 * Charges one failed guess against a challenge, and retires it if that was
	 * the last one allowed.
	 *
	 * <p>The {@code attempt_count < max_attempts} predicate keeps the increment
	 * inside the table's own CHECK constraint rather than relying on the caller
	 * having checked first.
	 *
	 * @return attempts remaining after this one
	 */
	public int recordFailedAttempt(long id, Instant now) {
		jdbc.update("""
				UPDATE otp_challenges
				   SET attempt_count = attempt_count + 1, updated_at = ?
				 WHERE id = ?
				   AND attempt_count < max_attempts
				""", Timestamp.from(now), id);

		Integer remaining = jdbc.queryForObject(
				"SELECT max_attempts - attempt_count FROM otp_challenges WHERE id = ?",
				Integer.class, id);
		int left = remaining == null ? 0 : remaining;

		if (left <= 0) {
			jdbc.update("""
					UPDATE otp_challenges
					   SET invalidated_at = ?, invalidated_reason = ?, updated_at = ?
					 WHERE id = ? AND invalidated_at IS NULL
					""",
					Timestamp.from(now), OtpInvalidationReason.EXHAUSTED.name(),
					Timestamp.from(now), id);
		}
		return left;
	}

	/** Sends to this identifier since {@code since}, for the per-identifier ceiling. */
	public int countByIdentifierSince(String identifierHash, Instant since) {
		Integer n = jdbc.queryForObject(
				"SELECT count(*) FROM otp_challenges WHERE identifier_hash = ? AND created_at >= ?",
				Integer.class, identifierHash, Timestamp.from(since));
		return n == null ? 0 : n;
	}

	/** Sends from this source address since {@code since}, for the per-IP ceiling. */
	public int countByIpSince(String ipHash, Instant since) {
		if (ipHash == null) {
			return 0;
		}
		Integer n = jdbc.queryForObject(
				"SELECT count(*) FROM otp_challenges WHERE ip_hash = ? AND created_at >= ?",
				Integer.class, ipHash, Timestamp.from(since));
		return n == null ? 0 : n;
	}

	/** When this identifier and purpose was last sent to, for the resend cooldown. */
	public Optional<Instant> lastIssuedAt(String identifierHash, OtpPurpose purpose) {
		List<Timestamp> rows = jdbc.queryForList("""
				SELECT created_at FROM otp_challenges
				 WHERE identifier_hash = ? AND purpose = ?
				 ORDER BY created_at DESC LIMIT 1
				""", Timestamp.class, identifierHash, purpose.name());
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0).toInstant());
	}

	/**
	 * Retention sweep. Deletes only rows older than the cutoff, whatever their
	 * state — a spent or expired row still carries rate-limiting and audit value
	 * until it ages out, so nothing is removed on the strength of being used.
	 *
	 * @return rows removed
	 */
	public int deleteOlderThan(Instant cutoff) {
		return jdbc.update("DELETE FROM otp_challenges WHERE created_at < ?", Timestamp.from(cutoff));
	}
}
