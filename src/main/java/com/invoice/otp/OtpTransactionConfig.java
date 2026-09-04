package com.invoice.otp;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * A JDBC transaction manager used only by the OTP subsystem.
 *
 * <p>Not a stylistic preference — the default one cannot do this job. Spring
 * Boot makes {@code JpaTransactionManager} the primary transaction manager, and
 * with {@code open-in-view} enabled an EntityManager is already bound to the
 * request thread before any OTP code runs. A transaction started through the
 * JPA manager therefore joins that existing EntityManager, and its connection
 * is released when OSIV closes the session at the end of the response — not at
 * commit.
 *
 * <p>On the OTP send path the response is not rendered until SMTP has answered,
 * so that connection is held for the entire mail exchange. Measured on the
 * running stack against a relay that accepts and then stalls: fifteen
 * concurrent sends leased all ten pool connections, an unrelated request waited
 * for the full 30-second Hikari timeout and then failed with 500, and the sends
 * themselves stretched to 40 seconds queueing for connections they had taken
 * from each other.
 *
 * <p>{@link DataSourceTransactionManager} takes a connection straight from the
 * pool and returns it at commit, with no EntityManager and nothing for OSIV to
 * hold. The OTP subsystem only ever touches {@code otp_challenges} and
 * {@code audit_log} through {@code JdbcTemplate}, so it needs nothing JPA
 * provides.
 *
 * <p><strong>Correctness note.</strong> This means OTP work commits
 * independently of any surrounding JPA transaction — {@code loginWithOtp} is
 * {@code @Transactional} and calls {@code verify} inside it. That separation is
 * wanted: consuming a passcode must not be undone by a later failure in the
 * login flow, or a code could be silently returned to circulation. The two
 * never contend, since JPA owns the identity tables and this owns
 * {@code otp_challenges}.
 */
@Configuration
public class OtpTransactionConfig {

	/**
	 * The JPA transaction manager, declared explicitly.
	 *
	 * <p>Required, and easy to miss. Spring Boot's auto-configuration creates
	 * this only {@code @ConditionalOnMissingBean(TransactionManager.class)}, so
	 * simply adding the OTP manager below makes Boot back off and stop creating
	 * it at all. Every {@code @Transactional} in the service then fails at
	 * runtime with "No bean named 'transactionManager' available" — a startup
	 * that looks clean and a login that returns 400. Found exactly that way.
	 *
	 * <p>Kept {@code @Primary} and named {@code transactionManager} so that
	 * every existing {@code @Transactional} resolves to it unchanged.
	 */
	@Bean("transactionManager")
	@Primary
	public PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
		return new JpaTransactionManager(factory);
	}

	/**
	 * Deliberately not {@code @Primary}: only the OTP subsystem asks for this,
	 * by qualifier. Everything else keeps using JPA above.
	 */
	@Bean("otpTransactionManager")
	public PlatformTransactionManager otpTransactionManager(DataSource dataSource) {
		return new DataSourceTransactionManager(dataSource);
	}
}
