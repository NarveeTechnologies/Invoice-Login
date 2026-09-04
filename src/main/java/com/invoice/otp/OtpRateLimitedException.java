package com.invoice.otp;

import java.time.Duration;

/**
 * A send was refused by a ceiling. Carries a Retry-After for the response and
 * nothing else — in particular not which ceiling, since telling an abusive
 * caller whether they hit the per-address or the per-IP limit tells them how to
 * spread their next attempt.
 */
public class OtpRateLimitedException extends RuntimeException {

	private final Duration retryAfter;

	public OtpRateLimitedException(Duration retryAfter) {
		super("Too many verification codes requested. Please wait before trying again.");
		this.retryAfter = retryAfter;
	}

	public Duration getRetryAfter() {
		return retryAfter;
	}

	public long getRetryAfterSeconds() {
		return Math.max(1, retryAfter.toSeconds());
	}
}
