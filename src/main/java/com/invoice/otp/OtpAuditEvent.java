package com.invoice.otp;

/**
 * Auditable moments in a passcode's life. The name is written verbatim into
 * {@code audit_log.action} and into the structured log line, so these strings
 * are an operational interface — renaming one breaks existing queries.
 */
public enum OtpAuditEvent {

	/** A send was asked for, before any limit was evaluated. */
	OTP_REQUESTED,

	/** A challenge was created and the mail handed to a provider. */
	OTP_SENT,

	/** A send was refused by a rate limit or the resend cooldown. */
	OTP_RATE_LIMITED,

	/** A code matched and the challenge was spent. */
	OTP_VERIFIED,

	/** A code did not match. */
	OTP_FAILED,

	/** Verification hit a challenge past its expiry. */
	OTP_EXPIRED,

	/** Verification hit a challenge that had already been spent — a replay. */
	OTP_CONSUMED,

	/** The attempt ceiling was reached and the challenge was burned. */
	OTP_EXHAUSTED,

	/** The provider refused the message. No usable passcode exists. */
	OTP_DELIVERY_FAILED,

	/** A registration was completed against a verified challenge. */
	OTP_REGISTRATION_VERIFIED,

	/** A password reset was completed against a verified challenge. */
	OTP_PASSWORD_RESET_VERIFIED
}
