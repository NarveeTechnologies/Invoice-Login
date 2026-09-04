package com.invoice.otp;

/**
 * Result of a verification attempt.
 *
 * @param outcome            the precise internal reason — for audit, not for the caller
 * @param attemptsRemaining  guesses left against this challenge, or 0
 * @param accountExists      whether the identifier resolved to an account; the
 *                           caller needs this to decide what to do next, and
 *                           must not put it in a response body
 */
public record OtpVerificationResult(
		OtpVerificationOutcome outcome,
		int attemptsRemaining,
		boolean accountExists) {

	public boolean isVerified() {
		return outcome.isSuccess();
	}

	/**
	 * The single message every failure produces.
	 *
	 * <p>One string for all of them on purpose. The legacy implementation
	 * answered "OTP not found for this email", "OTP has expired" and "Invalid
	 * OTP" as three distinct 400s from an endpoint that needed no
	 * authentication, which told an unauthenticated caller whether an address
	 * had a passcode outstanding and let them separate a wrong guess from a
	 * stale one.
	 */
	public static String userFacingFailureMessage() {
		return "That code is not valid or has expired. Please request a new one.";
	}
}
