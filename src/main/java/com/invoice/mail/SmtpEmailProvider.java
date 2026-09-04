package com.invoice.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.invoice.exception.MailDeliveryException;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;

/**
 * Delivers over SMTP through Spring's {@link JavaMailSender}.
 *
 * <p>Sends {@code multipart/alternative} so that a plain-text client shows the
 * text body and everything else shows the HTML.
 *
 * <p>This is the provider you get unless {@code invoice.mail.provider} names
 * another one. Exactly one provider bean is registered, by condition rather
 * than by selecting from a list at runtime, so there is no arrangement in which
 * two transports are both live and the wrong one wins.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "invoice.mail.provider", havingValue = "smtp", matchIfMissing = true)
public class SmtpEmailProvider implements EmailProvider {

	private final JavaMailSender mailSender;
	private final MailFromProperties from;

	public SmtpEmailProvider(JavaMailSender mailSender, MailFromProperties from) {
		this.mailSender = mailSender;
		this.from = from;
	}

	@Override
	public void send(EmailMessage message) {
		try {
			MimeMessage mime = mailSender.createMimeMessage();
			// true => multipart, so both alternatives travel together.
			MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
			helper.setFrom(from.getAddress(), from.getName());
			helper.setTo(message.to());
			helper.setSubject(message.subject());
			// text first, then HTML: MimeMessageHelper emits them in the order
			// multipart/alternative requires, least-capable variant first.
			helper.setText(message.textBody(), message.htmlBody());
			mailSender.send(mime);
		} catch (Exception e) {
			// The cause carries the host, port and SMTP status. It goes to the
			// log and never into the exception message, which is user-facing.
			log.error("SMTP delivery failed via provider={}", name(), e);
			throw new MailDeliveryException(
					"We could not send the email just now. Please try again in a moment.", e);
		}
	}

	@Override
	public String name() {
		return "smtp";
	}

	@Override
	public boolean deliversForReal() {
		return true;
	}
}
