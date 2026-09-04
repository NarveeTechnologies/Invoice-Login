package com.invoice.otp;

import java.time.Duration;

/**
 * Outcome of the pre-send limit checks.
 *
 * @param allowed    whether the send may proceed
 * @param reason     which ceiling stopped it, for audit; null when allowed
 * @param retryAfter how long the caller should wait; null when allowed
 */
public record OtpRateLimitDecision(boolean allowed, String reason, Duration retryAfter) {

	static OtpRateLimitDecision allow() {
		return new OtpRateLimitDecision(true, null, null);
	}

	static OtpRateLimitDecision deny(String reason, Duration retryAfter) {
		return new OtpRateLimitDecision(false, reason, retryAfter);
	}
}
