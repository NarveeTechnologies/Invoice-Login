package com.invoice.otp;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Records OTP events to two places, for two different readers.
 *
 * <p>The structured log line is the reliable channel — it cannot fail in a way
 * that affects the request. The {@code audit_log} row is the queryable one, and
 * it reuses the table Invoice-Login already keeps for role and user
 * administration rather than introducing a second, competing audit store.
 *
 * <p>Written with {@link JdbcTemplate} rather than the JPA repository, and that
 * is not a style preference. Spring Boot leaves {@code open-in-view} enabled, so
 * the first JPA call in a request binds an EntityManager that holds its pooled
 * connection until the response is rendered — not until the transaction ends. On
 * the OTP send path the response is not rendered until SMTP has answered, so a
 * single JPA audit write was enough to lease a connection for the whole mail
 * exchange. Measured against a stalled relay: fifteen concurrent sends leased
 * all ten connections and an unrelated request waited eight seconds. Going
 * through JDBC borrows a connection and returns it immediately, so the send path
 * never touches JPA and OSIV never acquires anything.
 *
 * <p>Each row is written outside any surrounding transaction, in its own
 * autocommit statement. Two consequences are deliberate: a refusal is still
 * recorded when the caller's work is abandoned, which matters because
 * {@code OTP_RATE_LIMITED} is exactly the event an abusive caller would
 * otherwise erase; and a failure to write audit never fails an authentication.
 * The second is a trade — it is logged at ERROR so the gap is visible, and the
 * structured line survives regardless.
 *
 * <p>What never appears in either channel: the passcode, its hash, the mail
 * password, the JWT secret, the pepper. The address is recorded, because
 * {@code audit_log.email} already carries addresses for every other action in
 * this service and because "why did this user never receive their code" is not
 * answerable without it. The {@code otp_challenges} table takes the opposite
 * side of that trade and stores only a MAC, since it is the security lookup
 * rather than the operational record.
 */
@Slf4j
@Component
public class OtpAuditLogger {

	private final JdbcTemplate jdbc;

	public OtpAuditLogger(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * @param email    the address involved, or null where none is known
	 * @param detail   short outcome note; must never contain a passcode
	 */
	public void record(OtpAuditEvent event, OtpPurpose purpose, UUID correlationId,
			String email, String ipHash, String detail) {

		log.info("otp audit event={} purpose={} correlationId={} ipHash={} detail=\"{}\"",
				event, purpose, correlationId, abbreviate(ipHash), detail);

		try {
			persist(event, purpose, correlationId, email, ipHash, detail);
		} catch (RuntimeException e) {
			log.error("otp audit row could not be written event={} correlationId={} — "
					+ "the structured log line above is the surviving record",
					event, correlationId, e);
		}
	}

	void persist(OtpAuditEvent event, OtpPurpose purpose, UUID correlationId,
			String email, String ipHash, String detail) {

		jdbc.update("""
				INSERT INTO audit_log (action, entity_name, email, performed_by, timestamp, details)
				VALUES (?, ?, ?, ?, ?, ?)
				""",
				event.name(),
				"otp_challenge",
				email,
				email,
				Timestamp.valueOf(LocalDateTime.now()),
				truncate("correlationId=" + correlationId
						+ " purpose=" + purpose
						+ " ipHash=" + abbreviate(ipHash)
						+ (detail == null || detail.isBlank() ? "" : " " + detail)));
	}

	/**
	 * Audit rows are for correlation, not for reversing a hash. Sixteen hex
	 * characters is ample to tie rows from one source together and is not a
	 * usable input to anything.
	 */
	private static String abbreviate(String hash) {
		if (hash == null) {
			return "none";
		}
		return hash.length() <= 16 ? hash : hash.substring(0, 16);
	}

	/** audit_log.details is varchar(2000). */
	private static String truncate(String s) {
		return s.length() <= 2000 ? s : s.substring(0, 2000);
	}
}
