package com.invoice.otp;

import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import com.invoice.mail.EmailNotificationService;
import com.invoice.mail.MailFromProperties;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Refuses to start on an OTP configuration that would fail silently at runtime.
 *
 * <p>Everything checked here shares one property: get it wrong and the service
 * starts, serves traffic, and mints passcodes that nobody can use — a state
 * indistinguishable from a healthy one until a user complains. That is the
 * failure mode {@link com.invoice.config.MailConfigurationGuard} was written
 * for, and this extends the same treatment to the OTP-specific settings.
 *
 * <p>Deliberately not covered: {@code jwt.secret} length, which
 * {@link com.invoice.serviceImpl.JwtServiceImpl} already asserts at 32 bytes,
 * and {@code spring.mail.username} / {@code password}, already asserted by
 * {@code MailConfigurationGuard}. Duplicating either would mean two places to
 * update and two error messages for one fault.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties({ OtpProperties.class, MailFromProperties.class })
public class OtpConfigurationGuard {

	/**
	 * Profiles that may run without real mail delivery. An allowlist rather
	 * than a denylist, so a profile nobody thought about — or no profile at all
	 * — is treated as production and held to the stricter rule.
	 */
	private static final Set<String> NON_PRODUCTION_PROFILES =
			Set.of("dev", "test", "local", "authztest");

	private static final int MIN_PEPPER_BYTES = 32;

	private final OtpProperties otp;
	private final MailFromProperties from;
	private final EmailNotificationService mail;
	private final Environment environment;

	public OtpConfigurationGuard(OtpProperties otp, MailFromProperties from,
			EmailNotificationService mail, Environment environment) {
		this.otp = otp;
		this.from = from;
		this.mail = mail;
		this.environment = environment;
	}

	@PostConstruct
	void validate() {
		requirePepper();
		requireSenderAddress();
		requireSaneLimits();
		requireRealDeliveryInProduction();

		log.info("otp configuration validated: length={} ttlSeconds={} maxAttempts={} "
				+ "resendCooldownSeconds={} maxPerIdentifierPerHour={} maxPerIpPerHour={} "
				+ "provider={} retentionDays={}",
				otp.getLength(), otp.getTtl().toSeconds(), otp.getMaxAttempts(),
				otp.getResendCooldown().toSeconds(), otp.getMaxPerIdentifierPerHour(),
				otp.getMaxPerIpPerHour(), mail.providerName(), otp.getRetention().toDays());
	}

	private void requirePepper() {
		String pepper = otp.getPepper();
		if (!StringUtils.hasText(pepper)) {
			throw new IllegalStateException(
					"invoice.otp.pepper is not set. It keys the HMAC that protects stored "
							+ "passcodes; without it there is nothing to protect them with. "
							+ "Supply OTP_PEPPER from the environment — there is no default. "
							+ "Generate one with: openssl rand -base64 48");
		}
		// Spring passes an unresolved placeholder through as a literal, so a
		// missing environment variable arrives looking like a valid value.
		if (pepper.startsWith("${") && pepper.endsWith("}")) {
			throw new IllegalStateException(
					"invoice.otp.pepper resolved to the literal placeholder " + pepper
							+ " — the environment variable it names is not set.");
		}
		int bytes = pepper.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
		if (bytes < MIN_PEPPER_BYTES) {
			throw new IllegalStateException(
					"invoice.otp.pepper is " + bytes + " bytes; at least " + MIN_PEPPER_BYTES
							+ " are required to key HMAC-SHA256 meaningfully.");
		}
	}

	private void requireSenderAddress() {
		if (!StringUtils.hasText(from.getAddress())) {
			throw new IllegalStateException(
					"invoice.mail.from.address is not set. Set MAIL_FROM, or configure it to "
							+ "fall back to spring.mail.username.");
		}
		if (!from.getAddress().contains("@")) {
			throw new IllegalStateException(
					"invoice.mail.from.address is not an email address: " + from.getAddress());
		}
	}

	private void requireSaneLimits() {
		if (otp.getLength() < 6) {
			throw new IllegalStateException(
					"invoice.otp.length is " + otp.getLength() + ". Below 6 characters the "
							+ "search space is small enough that the attempt ceiling is the only "
							+ "control left; raise it or accept a materially weaker passcode.");
		}
		if (otp.getLength() > 12) {
			throw new IllegalStateException(
					"invoice.otp.length is " + otp.getLength()
							+ ". Longer than 12 is a usability problem, not extra security.");
		}
		if (otp.getTtl().isZero() || otp.getTtl().isNegative()) {
			throw new IllegalStateException("invoice.otp.ttl must be positive");
		}
		if (otp.getTtl().toHours() > 1) {
			throw new IllegalStateException(
					"invoice.otp.ttl is " + otp.getTtl() + ". A passcode valid for more than an "
							+ "hour is a standing credential; cap it well below that.");
		}
		if (otp.getMaxAttempts() < 1) {
			throw new IllegalStateException("invoice.otp.max-attempts must be at least 1");
		}
		if (otp.getMaxAttempts() > 10) {
			throw new IllegalStateException(
					"invoice.otp.max-attempts is " + otp.getMaxAttempts()
							+ ". Above roughly 10, guessing becomes the cheaper attack.");
		}
		if (otp.getMaxPerIdentifierPerHour() < 1 || otp.getMaxPerIpPerHour() < 1) {
			throw new IllegalStateException(
					"invoice.otp hourly ceilings must be at least 1; a value of 0 would refuse "
							+ "every request. Set invoice.otp.cleanup-enabled or the ceilings "
							+ "deliberately rather than to zero.");
		}
		if (otp.getRetention().toDays() < 1) {
			throw new IllegalStateException(
					"invoice.otp.retention must be at least a day: the hourly rate limiters read "
							+ "this history, so sweeping it sooner would lift their ceilings.");
		}
	}

	private void requireRealDeliveryInProduction() {
		List<String> active = List.of(environment.getActiveProfiles());
		boolean nonProduction = !active.isEmpty()
				&& active.stream().allMatch(NON_PRODUCTION_PROFILES::contains);

		if (nonProduction || mail.providerDeliversForReal()) {
			return;
		}
		throw new IllegalStateException(
				"mail provider '" + mail.providerName() + "' does not deliver, and the active "
						+ "profile " + (active.isEmpty() ? "(none)" : active)
						+ " is treated as production. The service would mint passcodes that no "
						+ "user could ever receive while reporting success. Set "
						+ "invoice.mail.provider=smtp, or run under a dev/test/local profile.");
	}
}
