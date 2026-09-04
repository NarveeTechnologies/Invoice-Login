package com.invoice.mail;

import java.time.Duration;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.invoice.otp.OtpPurpose;

import lombok.extern.slf4j.Slf4j;

/**
 * The layer OTP logic talks to. Composes a message and hands it to whichever
 * {@link EmailProvider} this environment resolved, timing the handoff so an
 * operator can tell a slow relay from a broken one.
 *
 * <p>The delivery result is logged against the challenge's correlation id and
 * carries no passcode.
 */
@Slf4j
@Service
public class EmailNotificationService {

	private final EmailProvider provider;
	private final OtpEmailTemplate template;

	public EmailNotificationService(EmailProvider provider, OtpEmailTemplate template) {
		this.provider = provider;
		this.template = template;
	}

	/**
	 * Sends a passcode.
	 *
	 * <p>Synchronous and allowed to throw. That is the whole point: the caller
	 * must not be able to report success for a passcode that was never handed
	 * to a relay, so the failure travels up rather than being logged and
	 * swallowed. The bounded SMTP timeouts in {@code MailConfig} are what keep
	 * "synchronous" from meaning "indefinite".
	 *
	 * @throws com.invoice.exception.MailDeliveryException if the transport refused it
	 */
	public void sendOtp(String recipient, OtpPurpose purpose, String code,
			Duration ttl, UUID correlationId) {
		EmailMessage message = template.compose(recipient, purpose, code, ttl);
		long startedAt = System.nanoTime();
		try {
			provider.send(message);
			log.info("otp mail sent correlationId={} purpose={} provider={} latencyMs={}",
					correlationId, purpose, provider.name(), elapsedMs(startedAt));
		} catch (RuntimeException e) {
			log.error("otp mail failed correlationId={} purpose={} provider={} latencyMs={} reason={}",
					correlationId, purpose, provider.name(), elapsedMs(startedAt),
					e.getClass().getSimpleName());
			throw e;
		}
	}

	/** Name of the transport in use, for the startup guard and audit records. */
	public String providerName() {
		return provider.name();
	}

	public boolean providerDeliversForReal() {
		return provider.deliversForReal();
	}

	private static long elapsedMs(long startedAtNanos) {
		return (System.nanoTime() - startedAtNanos) / 1_000_000;
	}
}
