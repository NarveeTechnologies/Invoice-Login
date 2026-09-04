package com.invoice.mail;

/**
 * Transport for outbound mail.
 *
 * <p>The seam exists so that OTP business logic never touches SMTP. It knows
 * that a message was accepted or was not; it does not know a host, a port, a
 * TLS mode or a driver exception.
 */
public interface EmailProvider {

	/**
	 * Hands the message to the transport.
	 *
	 * @throws com.invoice.exception.MailDeliveryException if it could not be accepted
	 */
	void send(EmailMessage message);

	/**
	 * Short name for logs and for the startup guard. Appears in audit records so
	 * an operator can tell at a glance whether a given environment was really
	 * delivering mail.
	 */
	String name();

	/**
	 * Whether this provider actually delivers. A provider that returns false is
	 * refused at startup under a production profile by
	 * {@link com.invoice.otp.OtpConfigurationGuard} — an environment that mints
	 * passcodes nobody receives is worse than one that will not start.
	 */
	boolean deliversForReal();
}
