package com.invoice.otp;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored challenge, as read back from {@code otp_challenges}.
 *
 * <p>Carries no plaintext: not the passcode, not the address, not the IP. Every
 * identifying field is already a MAC by the time a row exists.
 */
public record OtpChallenge(
		long id,
		String identifierHash,
		OtpPurpose purpose,
		String codeHash,
		Instant expiresAt,
		Instant consumedAt,
		int attemptCount,
		int maxAttempts,
		Instant invalidatedAt,
		String invalidatedReason,
		Instant createdAt,
		UUID correlationId,
		boolean accountExists) {

	/** Whether this challenge can still be spent. */
	public boolean isLive(Instant now) {
		return consumedAt == null
				&& invalidatedAt == null
				&& now.isBefore(expiresAt)
				&& attemptCount < maxAttempts;
	}

	public boolean isExpired(Instant now) {
		return !now.isBefore(expiresAt);
	}

	public boolean isAttemptCeilingReached() {
		return attemptCount >= maxAttempts;
	}

	public int attemptsRemaining() {
		return Math.max(0, maxAttempts - attemptCount);
	}
}
