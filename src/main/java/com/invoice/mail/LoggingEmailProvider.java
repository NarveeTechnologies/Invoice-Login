package com.invoice.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Development transport. Records that a message would have been sent and drops
 * it.
 *
 * <p>Two things make this safe to have in the codebase.
 *
 * <p>It reports {@link #deliversForReal()} as false, and
 * {@link com.invoice.otp.OtpConfigurationGuard} refuses to start a production
 * profile that resolves to it. A silent logging provider in production is the
 * precise failure this design is built to prevent: passcodes would be minted,
 * the API would answer 200, and nobody would ever receive one.
 *
 * <p>And it never logs the body. The body is where the passcode is. It records
 * the recipient, the subject and the byte counts, which is enough to confirm
 * composition worked and not enough to authenticate as anybody.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "invoice.mail.provider", havingValue = "logging")
public class LoggingEmailProvider implements EmailProvider {

	@Override
	public void send(EmailMessage message) {
		log.warn("mail not delivered (provider=logging): to={} subject=\"{}\" "
				+ "textBytes={} htmlBytes={} — no message was sent",
				message.to(), message.subject(),
				message.textBody().length(), message.htmlBody().length());
	}

	@Override
	public String name() {
		return "logging";
	}

	@Override
	public boolean deliversForReal() {
		return false;
	}
}
