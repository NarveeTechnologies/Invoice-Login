package com.invoice.otp;

/**
 * What a passcode was minted for.
 *
 * <p>Purpose exists to stop one flow's passcode being spent in another. The
 * legacy {@code otp} table had a single row per email address and no purpose
 * column, and {@code findByEmailAndOtp(email, otp)} was the whole of login
 * verification — so a code mailed out by the account-number-change flow, or by
 * registration, satisfied a login just as well as a login code did. Every
 * lookup in {@link OtpChallengeRepository} binds identifier and purpose
 * together, and the purpose is folded into the code MAC as well, so the two
 * cannot be separated even by an attacker holding the database.
 */
public enum OtpPurpose {

	/** Sign-in to an existing account. */
	LOGIN,

	/** Proving control of an address before an account is created for it. */
	REGISTRATION,

	/** Re-authenticating an already-signed-in user before a bank-detail change. */
	ACCOUNT_NUMBER_CHANGE,

	/** Reserved for password recovery. No flow issues this yet. */
	PASSWORD_RESET;

	/**
	 * Whether a challenge for this purpose is expected to belong to an existing
	 * account. Registration is the one flow where the absence of an account is
	 * the normal case rather than a signal worth hiding.
	 */
	public boolean requiresExistingAccount() {
		return this != REGISTRATION;
	}
}
