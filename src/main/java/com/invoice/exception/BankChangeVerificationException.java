package com.invoice.exception;

/**
 * A bank-account change was submitted without a verified code.
 *
 * <p>Changing the account an invoice is paid into is how payments get
 * redirected, so the platform asks for a fresh code sent to the account
 * holder's own address first. Both front ends collected that code and neither
 * server endpoint ever checked it; this is the refusal the check now issues.
 * Mapped to 403: the caller is authenticated, and the record is their own — what
 * is missing is the proof of control.
 */
public class BankChangeVerificationException extends RuntimeException {
	public BankChangeVerificationException(String message) {
		super(message);
	}
}
