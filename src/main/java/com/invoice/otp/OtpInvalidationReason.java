package com.invoice.otp;

/**
 * Why a challenge was retired without being spent. Rows are marked rather than
 * deleted so that a resend cannot be used to wipe the rate-limiting history
 * that would otherwise throttle it.
 */
public enum OtpInvalidationReason {

	/** A newer challenge was issued for the same identifier and purpose. */
	SUPERSEDED,

	/** The attempt ceiling was reached. */
	EXHAUSTED,

	/**
	 * The passcode could not be handed to a mail provider, so nobody received
	 * it. Retired rather than deleted: the row still counts toward the rate
	 * limit, and an operator investigating "no email arrived" needs to see that
	 * the attempt happened and why it failed.
	 */
	DELIVERY_FAILED
}
