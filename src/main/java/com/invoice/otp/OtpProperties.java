package com.invoice.otp;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational limits for the OTP subsystem. Every value is environment-bound;
 * none is compiled in.
 *
 * <p>The defaults here are deliberately the safe end of each range rather than
 * the previous behaviour. The legacy implementation hardcoded a two-minute TTL
 * at three separate call sites and had no attempt ceiling, no resend cooldown
 * and no request ceiling of any kind.
 */
@ConfigurationProperties(prefix = "invoice.otp")
public class OtpProperties {

	/**
	 * Passcode length. Env: OTP_LENGTH.
	 *
	 * <p>Six matches what the clients already validate against. Each extra
	 * character multiplies the search space by 31, so raising it is cheap
	 * security and a small usability cost; lowering it below 6 is refused by
	 * {@link OtpConfigurationGuard}, since a 4-character code is only ~923k
	 * possibilities and the attempt ceiling is the only thing standing in front
	 * of it.
	 */
	private int length = 6;

	/** How long a passcode stays valid. Env: OTP_TTL_SECONDS. */
	private Duration ttl = Duration.ofMinutes(10);

	/** Wrong guesses allowed against one challenge before it is burned. Env: OTP_MAX_ATTEMPTS. */
	private int maxAttempts = 5;

	/** Minimum gap between two sends to the same address. Env: OTP_RESEND_COOLDOWN_SECONDS. */
	private Duration resendCooldown = Duration.ofSeconds(60);

	/** Sends allowed per address per hour. Env: OTP_MAX_PER_IDENTIFIER_PER_HOUR. */
	private int maxPerIdentifierPerHour = 5;

	/** Sends allowed per source IP per hour. Env: OTP_MAX_PER_IP_PER_HOUR. */
	private int maxPerIpPerHour = 20;

	/** How long spent and expired challenges are kept for audit and rate limiting. */
	private Duration retention = Duration.ofDays(30);

	/** Whether the scheduled retention sweep runs. */
	private boolean cleanupEnabled = true;

	/**
	 * Server-side pepper for the identifier and code MACs. Mandatory: there is
	 * no default, and {@link OtpConfigurationGuard} refuses to start without it.
	 * Rotating it invalidates every live challenge, which is safe — they expire
	 * within {@link #ttl} anyway.
	 */
	private String pepper;

	public int getLength() {
		return length;
	}

	public void setLength(int length) {
		this.length = length;
	}

	public Duration getTtl() {
		return ttl;
	}

	public void setTtl(Duration ttl) {
		this.ttl = ttl;
	}

	public int getMaxAttempts() {
		return maxAttempts;
	}

	public void setMaxAttempts(int maxAttempts) {
		this.maxAttempts = maxAttempts;
	}

	public Duration getResendCooldown() {
		return resendCooldown;
	}

	public void setResendCooldown(Duration resendCooldown) {
		this.resendCooldown = resendCooldown;
	}

	public int getMaxPerIdentifierPerHour() {
		return maxPerIdentifierPerHour;
	}

	public void setMaxPerIdentifierPerHour(int maxPerIdentifierPerHour) {
		this.maxPerIdentifierPerHour = maxPerIdentifierPerHour;
	}

	public int getMaxPerIpPerHour() {
		return maxPerIpPerHour;
	}

	public void setMaxPerIpPerHour(int maxPerIpPerHour) {
		this.maxPerIpPerHour = maxPerIpPerHour;
	}

	public Duration getRetention() {
		return retention;
	}

	public void setRetention(Duration retention) {
		this.retention = retention;
	}

	public boolean isCleanupEnabled() {
		return cleanupEnabled;
	}

	public void setCleanupEnabled(boolean cleanupEnabled) {
		this.cleanupEnabled = cleanupEnabled;
	}

	public String getPepper() {
		return pepper;
	}

	public void setPepper(String pepper) {
		this.pepper = pepper;
	}
}
