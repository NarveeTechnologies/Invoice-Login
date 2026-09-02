package com.invoice.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Fails startup when mail credentials are absent.
 *
 * <p>Unlike {@code jwt.secret}, an unresolved {@code spring.mail.password} does NOT
 * stop the context: Spring binds the unexpanded {@code ${MAIL_PASSWORD}} through to
 * {@code MailProperties} as a literal, so the service starts happily and every send
 * then fails SMTP authentication at runtime. Verified against a live container —
 * with MAIL_PASSWORD unset the application logged
 * "Started …Application" and served traffic.
 *
 * <p>That is the wrong failure mode for a service whose login flow depends on
 * delivering a one-time passcode. Checking here makes mail credentials behave like
 * every other secret: absent means the service does not start.
 */
@Configuration
public class MailConfigurationGuard {

	@Value("${spring.mail.password:}")
	private String mailPassword;

	@Value("${spring.mail.username:}")
	private String mailUsername;

	@Value("${spring.mail.host:}")
	private String mailHost;

	@PostConstruct
	void assertMailCredentialsPresent() {
		if (!StringUtils.hasText(mailHost)) {
			return; // no mail configured at all — nothing to guard
		}
		requireResolved("spring.mail.username", mailUsername);
		requireResolved("spring.mail.password", mailPassword);
	}

	private static void requireResolved(String property, String value) {
		if (!StringUtils.hasText(value)) {
			throw new IllegalStateException(
					property + " is not set. Supply it from the environment; there is no default.");
		}
		// An unexpanded placeholder means the environment variable is missing. Spring
		// passes it through silently, so catch it explicitly.
		if (value.startsWith("${") && value.endsWith("}")) {
			throw new IllegalStateException(
					property + " resolved to the literal placeholder " + value
							+ " — the environment variable it names is not set. Mail would fail"
							+ " authentication on every send instead of failing here.");
		}
	}
}
