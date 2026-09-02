package com.invoice.exception;

/**
 * Raised when an outbound email could not be handed to the mail server.
 *
 * <p>Previously these failures were caught and logged, and the caller returned
 * success. For a one-time passcode that is the worst possible outcome: the UI
 * reports "OTP sent", the user waits for a message that will never arrive, and
 * nothing distinguishes a working system from a broken one until somebody
 * complains. Delivery failure must reach the caller.
 *
 * <p>The message on this exception is shown to end users, so it carries no mail
 * host, account, or driver detail. The underlying cause is attached for the logs
 * and is never rendered.
 */
public class MailDeliveryException extends RuntimeException {

	public MailDeliveryException(String message, Throwable cause) {
		super(message, cause);
	}
}
