package com.invoice.mail;

/**
 * A composed message, ready for whichever provider is wired in.
 *
 * <p>Both bodies are mandatory. An OTP mail that exists only as HTML is
 * unreadable in a plain-text client and scores worse with spam filters, and the
 * previous implementation emitted HTML only at all three call sites.
 */
public record EmailMessage(
		String to,
		String subject,
		String textBody,
		String htmlBody) {

	public EmailMessage {
		if (to == null || to.isBlank()) {
			throw new IllegalArgumentException("recipient is required");
		}
		if (subject == null || subject.isBlank()) {
			throw new IllegalArgumentException("subject is required");
		}
		if (textBody == null || textBody.isBlank()) {
			throw new IllegalArgumentException("a plain-text body is required alongside the HTML");
		}
		if (htmlBody == null || htmlBody.isBlank()) {
			throw new IllegalArgumentException("an HTML body is required");
		}
	}
}
