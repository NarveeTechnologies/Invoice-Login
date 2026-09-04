package com.invoice.otp;

/**
 * Why a verification ended as it did.
 *
 * <p>This is the internal, precise answer, used for audit and for the metrics
 * an operator needs. It is never rendered to a caller: every failing value maps
 * to one identical user-facing message, because the differences between them —
 * no challenge exists, the code was wrong, it expired, it was already spent —
 * are exactly the facts an attacker would use to steer.
 */
public enum OtpVerificationOutcome {

	VERIFIED(true),

	/** Nothing was ever issued for this identifier and purpose. */
	NO_CHALLENGE(false),

	/** Submitted string could not be one of our codes. Costs no attempt. */
	MALFORMED(false),

	WRONG_CODE(false),

	EXPIRED(false),

	/** Already spent — a replay, or a genuine double submit. */
	ALREADY_CONSUMED(false),

	/** Retired by a resend, or burned by hitting the attempt ceiling. */
	INVALIDATED(false),

	ATTEMPTS_EXHAUSTED(false);

	private final boolean success;

	OtpVerificationOutcome(boolean success) {
		this.success = success;
	}

	public boolean isSuccess() {
		return success;
	}
}
