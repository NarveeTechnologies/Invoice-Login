package com.invoice.otp;

import java.time.Duration;
import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

/**
 * Ages out old challenges.
 *
 * <p>The retention period is chosen for the rate limiter and the audit trail,
 * not for the passcode. A row stops being a credential the moment it expires,
 * within minutes — but the hourly ceilings in {@link OtpRateLimiter} are
 * computed from these rows, and an investigation into a suspicious sign-in
 * needs them for far longer than that. So nothing is deleted for having been
 * spent, expired or failed; rows go only when they are older than
 * {@code invoice.otp.retention}, whatever state they are in.
 *
 * <p>{@link OtpConfigurationGuard} refuses a retention under one day for the
 * same reason: setting it shorter than the rate-limit window would quietly
 * raise the ceilings by deleting the evidence they count.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "invoice.otp.cleanup-enabled", havingValue = "true",
		matchIfMissing = true)
public class OtpRetentionSweeper {

	private final OtpChallengeRepository repository;
	private final OtpProperties properties;

	public OtpRetentionSweeper(OtpChallengeRepository repository, OtpProperties properties) {
		this.repository = repository;
		this.properties = properties;
	}

	/**
	 * Hourly, offset from the top of the hour so the sweep does not land with
	 * every other scheduled job in the platform.
	 */
	@Scheduled(cron = "${invoice.otp.cleanup-cron:0 17 * * * *}")
	@Transactional
	public void sweep() {
		Duration retention = properties.getRetention();
		Instant cutoff = Instant.now().minus(retention);
		try {
			int removed = repository.deleteOlderThan(cutoff);
			if (removed > 0) {
				log.info("otp retention sweep removed={} olderThan={} retentionDays={}",
						removed, cutoff, retention.toDays());
			}
		} catch (RuntimeException e) {
			// A failed sweep grows a table. It must never take the service down.
			log.error("otp retention sweep failed; will retry on the next schedule", e);
		}
	}
}
