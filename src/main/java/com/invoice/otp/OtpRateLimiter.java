package com.invoice.otp;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Abuse ceilings for passcode sends.
 *
 * <p>Counted out of {@code otp_challenges} rather than from an in-memory
 * counter or Redis. Three reasons decided that. Invoice-Login runs with no
 * Redis dependency today, and adding one to the authentication service to hold
 * a counter that a table already implies is a new failure mode for no new
 * capability. A process-local counter would be wrong the moment the service is
 * replicated, and would reset on every deploy — an attacker's ceiling should not
 * be lifted by a restart. And the rows have to exist anyway.
 *
 * <p>This is why {@link OtpChallengeRepository#invalidateLive} marks rows
 * instead of deleting them. The legacy flow deleted by email on every send, so
 * the history a limiter needs was destroyed by the very requests it was meant
 * to throttle.
 *
 * <p>The window is a sliding one over {@code created_at}, so there is no
 * boundary at which a fixed bucket resets and a caller gets a fresh allowance.
 */
@Component
public class OtpRateLimiter {

	private final OtpChallengeRepository repository;
	private final OtpProperties properties;

	public OtpRateLimiter(OtpChallengeRepository repository, OtpProperties properties) {
		this.repository = repository;
		this.properties = properties;
	}

	/**
	 * Evaluated before a challenge is created, in ascending order of how much
	 * the answer tells the caller: the cooldown is about their own last request,
	 * the identifier ceiling about that address, the IP ceiling about the source.
	 */
	public OtpRateLimitDecision check(String identifierHash, String ipHash,
			OtpPurpose purpose, Instant now) {

		Optional<Instant> lastIssued = repository.lastIssuedAt(identifierHash, purpose);
		if (lastIssued.isPresent()) {
			Duration sinceLast = Duration.between(lastIssued.get(), now);
			Duration cooldown = properties.getResendCooldown();
			if (sinceLast.compareTo(cooldown) < 0) {
				return OtpRateLimitDecision.deny("resend_cooldown", cooldown.minus(sinceLast));
			}
		}

		Instant windowStart = now.minus(Duration.ofHours(1));

		int perIdentifier = repository.countByIdentifierSince(identifierHash, windowStart);
		if (perIdentifier >= properties.getMaxPerIdentifierPerHour()) {
			return OtpRateLimitDecision.deny("identifier_hourly_limit", untilWindowFrees(now));
		}

		int perIp = repository.countByIpSince(ipHash, windowStart);
		if (perIp >= properties.getMaxPerIpPerHour()) {
			return OtpRateLimitDecision.deny("ip_hourly_limit", untilWindowFrees(now));
		}

		return OtpRateLimitDecision.allow();
	}

	/**
	 * A deliberately coarse Retry-After. Reporting the exact moment the oldest
	 * request ages out would let a caller map the contents of the window, so a
	 * caller who has spent their hour is simply told to come back in a quarter
	 * of one.
	 */
	private static Duration untilWindowFrees(Instant now) {
		return Duration.ofMinutes(15);
	}
}
