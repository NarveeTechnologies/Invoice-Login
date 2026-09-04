package com.invoice.config;

import java.util.Properties;

import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import lombok.extern.slf4j.Slf4j;

/**
 * Builds the {@link JavaMailSender} from configuration.
 *
 * <p><strong>History, because it explains the shape of this class.</strong>
 * Port, auth and SSL were once hardcoded here for the production relay
 * (host.narveetech.com:465) while the host came from a property — so
 * {@code SPRING_MAIL_HOST} could repoint the service while the port and the
 * TLS handshake stayed pinned to production values. Against the UAT Mailpit
 * sink on 1025 that made delivery impossible:
 *
 * <pre>MailConnectException: Couldn't connect to host, port: mailpit, 465</pre>
 *
 * <p>That was fixed by reading each of those three from configuration. This
 * revision fixes what that fix left behind. The bean was still assembling its
 * own {@link Properties} from a hand-written list of three keys, so every other
 * {@code spring.mail.properties.*} entry was silently discarded — including the
 * three timeouts that {@code application.properties} has declared all along:
 *
 * <pre>
 * spring.mail.properties.mail.smtp.connectiontimeout=5000
 * spring.mail.properties.mail.smtp.timeout=10000
 * spring.mail.properties.mail.smtp.writetimeout=10000
 * </pre>
 *
 * <p>With those dropped, JavaMail's defaults apply, and JavaMail's defaults are
 * "wait forever". That is not a cosmetic omission in this service: OTP sends
 * are synchronous and transactional by design, so a relay that accepts a TCP
 * connection and then stalls would hold a request thread and an open database
 * transaction indefinitely, and enough of them would exhaust the Hikari pool and
 * take down authentication altogether. A blackholed SMTP port is a much more
 * common failure than a refused one.
 *
 * <p>So the property map now comes from Spring Boot's own
 * {@link MailProperties} binding, which carries the whole
 * {@code spring.mail.properties.*} tree rather than a list someone has to
 * remember to extend. The three timeouts are then defaulted if absent, so no
 * configuration — not even an empty one — can produce an unbounded sender.
 */
@Slf4j
@Configuration
// MailProperties is normally registered by MailSenderAutoConfiguration, which
// backs off here: it is @ConditionalOnMissingBean(MailSender.class) and the bean
// below is one. Without this the constructor injection would fail at startup.
@EnableConfigurationProperties(MailProperties.class)
public class MailConfig {

	/** Applied only when configuration does not set them. Milliseconds. */
	private static final String DEFAULT_CONNECTION_TIMEOUT_MS = "5000";
	private static final String DEFAULT_READ_TIMEOUT_MS = "10000";
	private static final String DEFAULT_WRITE_TIMEOUT_MS = "10000";

	private final MailProperties mailProperties;

	public MailConfig(MailProperties mailProperties) {
		this.mailProperties = mailProperties;
	}

	@Bean
	public JavaMailSender javaMailSender() {
		JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
		mailSender.setHost(mailProperties.getHost());
		if (mailProperties.getPort() != null) {
			mailSender.setPort(mailProperties.getPort());
		}
		mailSender.setUsername(mailProperties.getUsername());
		mailSender.setPassword(mailProperties.getPassword());
		mailSender.setProtocol(mailProperties.getProtocol());
		mailSender.setDefaultEncoding(
				mailProperties.getDefaultEncoding() != null
						? mailProperties.getDefaultEncoding().name()
						: "UTF-8");

		Properties props = mailSender.getJavaMailProperties();
		// The whole spring.mail.properties.* tree, verbatim. auth, ssl.enable
		// and starttls.enable arrive through here like everything else.
		props.putAll(mailProperties.getProperties());

		// Bounded by default. putIfAbsent so an explicit value always wins.
		props.putIfAbsent("mail.smtp.connectiontimeout", DEFAULT_CONNECTION_TIMEOUT_MS);
		props.putIfAbsent("mail.smtp.timeout", DEFAULT_READ_TIMEOUT_MS);
		props.putIfAbsent("mail.smtp.writetimeout", DEFAULT_WRITE_TIMEOUT_MS);

		// The same three under the smtps protocol name, which is what JavaMail
		// consults when mail.smtp.ssl.enable puts the session on implicit TLS —
		// the production configuration on port 465. Setting only the mail.smtp.*
		// forms would leave production unbounded while UAT looked correct.
		props.putIfAbsent("mail.smtps.connectiontimeout",
				props.getProperty("mail.smtp.connectiontimeout"));
		props.putIfAbsent("mail.smtps.timeout", props.getProperty("mail.smtp.timeout"));
		props.putIfAbsent("mail.smtps.writetimeout", props.getProperty("mail.smtp.writetimeout"));

		log.info("mail sender configured host={} port={} auth={} ssl={} starttls={} "
				+ "connectTimeoutMs={} readTimeoutMs={} writeTimeoutMs={}",
				mailSender.getHost(), mailSender.getPort(),
				props.getProperty("mail.smtp.auth", "unset"),
				props.getProperty("mail.smtp.ssl.enable", "unset"),
				props.getProperty("mail.smtp.starttls.enable", "unset"),
				props.getProperty("mail.smtp.connectiontimeout"),
				props.getProperty("mail.smtp.timeout"),
				props.getProperty("mail.smtp.writetimeout"));

		return mailSender;
	}
}
