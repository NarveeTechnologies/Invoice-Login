package com.invoice.otp;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.invoice.exception.MailDeliveryException;
import com.invoice.mail.EmailNotificationService;

import lombok.extern.slf4j.Slf4j;

/**
 * Issues and verifies one-time passcodes.
 *
 * <p>This is the only place that ever holds a passcode in plaintext, and it
 * holds one for the duration of a single {@link #request} call: long enough to
 * put it in an email. It is never returned, never logged and never persisted in
 * that form.
 */
@Slf4j
@Service
public class OtpService {

	private final OtpChallengeRepository repository;
	private final OtpCodeGenerator generator;
	private final OtpHasher hasher;
	private final OtpRateLimiter rateLimiter;
	private final OtpAuditLogger audit;
	private final EmailNotificationService mail;
	private final OtpProperties properties;
	/** Explicit boundaries: SMTP must sit outside every one of them. */
	private final TransactionTemplate transactions;

	public OtpService(OtpChallengeRepository repository, OtpCodeGenerator generator,
			OtpHasher hasher, OtpRateLimiter rateLimiter, OtpAuditLogger audit,
			EmailNotificationService mail, OtpProperties properties,
			@Qualifier("otpTransactionManager") PlatformTransactionManager transactionManager) {
		this.repository = repository;
		this.generator = generator;
		this.hasher = hasher;
		this.rateLimiter = rateLimiter;
		this.audit = audit;
		this.mail = mail;
		this.properties = properties;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	/**
	 * Issues a passcode, and mails it if the identifier warrants one.
	 *
	 * <p>The caller supplies {@code accountLookup} rather than an answer, so
	 * that this method controls the order of work and therefore what an observer
	 * can infer from it.
	 *
	 * <p><strong>On enumeration.</strong> For a purpose that expects an existing
	 * account, a request for an unknown address takes the same path as a known
	 * one — the limits are evaluated, a challenge row is written with
	 * {@code account_exists = false}, and the same uniform result comes back. No
	 * mail is sent, because sending to an address that has no account would make
	 * this endpoint a mailer for arbitrary third parties. That leaves one
	 * residual signal, the SMTP round trip missing from the unknown-address
	 * path, and it is documented rather than papered over with a sleep. It also
	 * matters far less than it appears: {@code GET /auth/check-email/{email}}
	 * needs no authentication and answers the same question directly, so this
	 * path is not the cheapest oracle in the service and closing it alone would
	 * change nothing. See docs/INVOICE_OTP_SECURITY.md.
	 *
	 * <p>Registration is deliberately different and does report a collision:
	 * a user who already has an account has to be told to sign in instead.
	 *
	 * <p><strong>Transaction boundaries.</strong> This method is deliberately
	 * <em>not</em> {@code @Transactional}. It runs two short transactions with
	 * the SMTP call between them, holding no database connection while the mail
	 * server is being talked to.
	 *
	 * <p>It used to be one transaction wrapping the send, which reads as the
	 * safer arrangement — a delivery failure rolled the challenge back, so an
	 * undelivered passcode could never stay valid. The cost was hidden and
	 * worse: every in-flight OTP request held a Hikari connection for the whole
	 * SMTP exchange. Against a relay that accepts a connection and then stalls,
	 * each request pins a connection for up to
	 * {@code MAIL_CONNECTION_TIMEOUT + MAIL_TIMEOUT}. The pool is ten. Eleven
	 * concurrent sign-in attempts and every database-backed request in the
	 * service — not just OTP — begins queueing for a connection, so a slow mail
	 * provider becomes a total outage.
	 *
	 * <p>The rollback guarantee is kept by other means: if the send throws, the
	 * challenge is retired explicitly in a second short transaction with reason
	 * {@code DELIVERY_FAILED}. The one behaviour that changes is a crash between
	 * the two, which leaves a challenge nobody ever received — harmless, since
	 * no one holds the code and it expires on schedule.
	 *
	 * @param accountLookup whether an account exists for the normalised address
	 * @return the correlation id, for the caller to return in a header
	 * @throws OtpRateLimitedException if a ceiling refused the send
	 * @throws MailDeliveryException   if the mail could not be handed to a provider
	 */
	public UUID request(String rawIdentifier, OtpPurpose purpose,
			Predicate<String> accountLookup, OtpRequestContext context) {

		String identifier = OtpHasher.normaliseIdentifier(rawIdentifier);
		String ipHash = hasher.hashOpaque(context.ipAddress());
		UUID correlationId = UUID.randomUUID();

		audit.record(OtpAuditEvent.OTP_REQUESTED, purpose, correlationId, identifier, ipHash, "");

		// ---- transaction 1: decide, persist, commit, release the connection.
		PreparedChallenge prepared = transactions.execute(status ->
				prepareChallenge(identifier, purpose, accountLookup, context, correlationId, ipHash));

		if (prepared == null || !prepared.shouldSend()) {
			// Uniform outcome, no mail. The row exists so the request counts
			// against the ceilings and an operator can see the attempt.
			log.info("otp challenge recorded without delivery correlationId={} purpose={} "
					+ "reason=no_account", correlationId, purpose);
			return correlationId;
		}

		// ---- no transaction is open here, and that is the whole point.
		try {
			mail.sendOtp(identifier, purpose, prepared.code(), properties.getTtl(), correlationId);
		} catch (RuntimeException e) {
			// ---- transaction 2: retire the challenge nobody received.
			transactions.execute(status -> repository.invalidateById(
					prepared.challengeId(), OtpInvalidationReason.DELIVERY_FAILED, Instant.now()));
			audit.record(OtpAuditEvent.OTP_DELIVERY_FAILED, purpose, correlationId,
					identifier, ipHash, "provider=" + mail.providerName()
							+ " challengeInvalidated=true");
			throw e;
		}

		audit.record(OtpAuditEvent.OTP_SENT, purpose, correlationId, identifier, ipHash,
				"provider=" + mail.providerName()
						+ " ttlSeconds=" + properties.getTtl().toSeconds()
						+ " superseded=" + prepared.superseded());
		return correlationId;
	}

	/**
	 * Everything that touches the database on the request path, in one short
	 * transaction: the ceilings, the account lookup, retiring the predecessor,
	 * and the insert.
	 *
	 * <p>Kept together because the limit check and the insert must be atomic
	 * with respect to each other — split them and two concurrent requests can
	 * both pass a ceiling that only had room for one.
	 */
	private PreparedChallenge prepareChallenge(String identifier, OtpPurpose purpose,
			Predicate<String> accountLookup, OtpRequestContext context,
			UUID correlationId, String ipHash) {

		String identifierHash = hasher.hashIdentifier(identifier);
		String userAgentHash = hasher.hashOpaque(context.userAgent());
		Instant now = Instant.now();

		OtpRateLimitDecision decision = rateLimiter.check(identifierHash, ipHash, purpose, now);
		if (!decision.allowed()) {
			audit.record(OtpAuditEvent.OTP_RATE_LIMITED, purpose, correlationId,
					identifier, ipHash, "reason=" + decision.reason());
			throw new OtpRateLimitedException(decision.retryAfter());
		}

		boolean accountExists = accountLookup.test(identifier);

		// A resend must not leave its predecessor spendable. Marked, not
		// deleted: the rate limiter reads this history, and deleting it here is
		// what let the legacy flow be used to clear its own tracks.
		int superseded = repository.invalidateLive(
				identifierHash, purpose, OtpInvalidationReason.SUPERSEDED, now);

		String code = generator.generate();
		long challengeId = repository.insert(identifierHash, purpose,
				hasher.hashCode(purpose, identifier, code),
				now.plus(properties.getTtl()), properties.getMaxAttempts(),
				ipHash, userAgentHash, correlationId, accountExists, now);

		boolean shouldSend = accountExists || !purpose.requiresExistingAccount();
		return new PreparedChallenge(challengeId, code, shouldSend, superseded);
	}

	/**
	 * A committed challenge and the one plaintext copy of its passcode, carried
	 * from the transaction that created it to the send that follows. Lives only
	 * on the stack, for the length of one request.
	 */
	private record PreparedChallenge(long challengeId, String code, boolean shouldSend,
			int superseded) {
	}

	/**
	 * Checks a submitted code and, on a match, spends it.
	 *
	 * <p>Runs in its own short transaction on the OTP transaction manager, so the
	 * row lock taken below is held for the shortest possible time and is not
	 * bound to whatever longer unit of work a caller such as login has open.
	 *
	 * <p>The sequence is: reject malformed input before touching the database,
	 * so junk costs no attempt; take the row lock, which is what makes two
	 * simultaneous submissions of the same code resolve to one success and one
	 * failure; then check invalidation, expiry and the attempt ceiling before
	 * comparing anything, so a burned challenge cannot be probed further.
	 */
	public OtpVerificationResult verify(String rawIdentifier, OtpPurpose purpose,
			String submittedCode, OtpRequestContext context) {
		// Runs on the OTP transaction manager, not the JPA one, for the reason
		// given in OtpTransactionConfig. REQUIRES_NEW in spirit: the row lock
		// below is held for the shortest possible time and is not bound to
		// whatever longer unit of work a caller such as login has open.
		return transactions.execute(status ->
				verifyInTransaction(rawIdentifier, purpose, submittedCode, context));
	}

	private OtpVerificationResult verifyInTransaction(String rawIdentifier, OtpPurpose purpose,
			String submittedCode, OtpRequestContext context) {

		String identifier = OtpHasher.normaliseIdentifier(rawIdentifier);
		String identifierHash = hasher.hashIdentifier(identifier);
		String ipHash = hasher.hashOpaque(context.ipAddress());
		Instant now = Instant.now();

		if (!generator.isWellFormed(submittedCode)) {
			audit.record(OtpAuditEvent.OTP_FAILED, purpose, null, identifier, ipHash,
					"outcome=MALFORMED");
			return new OtpVerificationResult(OtpVerificationOutcome.MALFORMED, 0, false);
		}

		Optional<OtpChallenge> maybe = repository.lockLatest(identifierHash, purpose);
		if (maybe.isEmpty()) {
			audit.record(OtpAuditEvent.OTP_FAILED, purpose, null, identifier, ipHash,
					"outcome=NO_CHALLENGE");
			return new OtpVerificationResult(OtpVerificationOutcome.NO_CHALLENGE, 0, false);
		}

		OtpChallenge challenge = maybe.get();
		UUID correlationId = challenge.correlationId();

		if (challenge.consumedAt() != null) {
			audit.record(OtpAuditEvent.OTP_CONSUMED, purpose, correlationId, identifier, ipHash,
					"outcome=ALREADY_CONSUMED replay=true");
			return new OtpVerificationResult(
					OtpVerificationOutcome.ALREADY_CONSUMED, 0, challenge.accountExists());
		}

		if (challenge.invalidatedAt() != null) {
			OtpVerificationOutcome outcome =
					OtpInvalidationReason.EXHAUSTED.name().equals(challenge.invalidatedReason())
							? OtpVerificationOutcome.ATTEMPTS_EXHAUSTED
							: OtpVerificationOutcome.INVALIDATED;
			audit.record(OtpAuditEvent.OTP_FAILED, purpose, correlationId, identifier, ipHash,
					"outcome=" + outcome + " reason=" + challenge.invalidatedReason());
			return new OtpVerificationResult(outcome, 0, challenge.accountExists());
		}

		if (challenge.isExpired(now)) {
			audit.record(OtpAuditEvent.OTP_EXPIRED, purpose, correlationId, identifier, ipHash,
					"outcome=EXPIRED");
			return new OtpVerificationResult(
					OtpVerificationOutcome.EXPIRED, 0, challenge.accountExists());
		}

		if (challenge.isAttemptCeilingReached()) {
			audit.record(OtpAuditEvent.OTP_EXHAUSTED, purpose, correlationId, identifier, ipHash,
					"outcome=ATTEMPTS_EXHAUSTED");
			return new OtpVerificationResult(
					OtpVerificationOutcome.ATTEMPTS_EXHAUSTED, 0, challenge.accountExists());
		}

		String submittedHash = hasher.hashCode(purpose, identifier, submittedCode);
		if (!OtpHasher.matches(challenge.codeHash(), submittedHash)) {
			int remaining = repository.recordFailedAttempt(challenge.id(), now);
			audit.record(OtpAuditEvent.OTP_FAILED, purpose, correlationId, identifier, ipHash,
					"outcome=WRONG_CODE attemptsRemaining=" + remaining);
			if (remaining <= 0) {
				audit.record(OtpAuditEvent.OTP_EXHAUSTED, purpose, correlationId,
						identifier, ipHash, "burned=true");
			}
			return new OtpVerificationResult(
					OtpVerificationOutcome.WRONG_CODE, remaining, challenge.accountExists());
		}

		// Conditional on consumed_at still being null. Belt and braces next to
		// the row lock: if this returns false something else spent it, and that
		// is a replay however it happened.
		if (!repository.consume(challenge.id(), now)) {
			audit.record(OtpAuditEvent.OTP_CONSUMED, purpose, correlationId, identifier, ipHash,
					"outcome=ALREADY_CONSUMED raced=true");
			return new OtpVerificationResult(
					OtpVerificationOutcome.ALREADY_CONSUMED, 0, challenge.accountExists());
		}

		audit.record(OtpAuditEvent.OTP_VERIFIED, purpose, correlationId, identifier, ipHash,
				"outcome=VERIFIED");
		return new OtpVerificationResult(
				OtpVerificationOutcome.VERIFIED, challenge.attemptsRemaining(),
				challenge.accountExists());
	}

	/** Records the completion of a flow that a verified challenge gated. */
	public void recordFlowCompletion(OtpPurpose purpose, String identifier, String ipHash) {
		OtpAuditEvent event = switch (purpose) {
			case REGISTRATION -> OtpAuditEvent.OTP_REGISTRATION_VERIFIED;
			case PASSWORD_RESET -> OtpAuditEvent.OTP_PASSWORD_RESET_VERIFIED;
			default -> OtpAuditEvent.OTP_VERIFIED;
		};
		audit.record(event, purpose, null, identifier, ipHash, "flowCompleted=true");
	}
}
