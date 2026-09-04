package com.invoice.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Envelope sender. Separated from {@code spring.mail.username} because the two
 * are not the same thing: the username authenticates to the relay, the From
 * address is what a recipient sees. The previous code used the username for
 * both, which works only while the relay account and the public sender happen
 * to coincide.
 */
@ConfigurationProperties(prefix = "invoice.mail.from")
public class MailFromProperties {

	/** Env: MAIL_FROM. Falls back to spring.mail.username via configuration. */
	private String address;

	/** Env: MAIL_FROM_NAME. */
	private String name = "Invoice";

	public String getAddress() {
		return address;
	}

	public void setAddress(String address) {
		this.address = address;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}
}
