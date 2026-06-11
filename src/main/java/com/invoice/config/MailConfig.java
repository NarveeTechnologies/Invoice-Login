package com.invoice.config;

import java.util.Properties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Configuration
public class MailConfig {

	@Value("${spring.mail.host}")
	private String mailHost;

	@Value("${spring.mail.username}")
	private String mailUsername;

	@Value("${spring.mail.password}")
	private String mailPassword;

	@Bean
	public JavaMailSender javaMailSender() {
		JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
		mailSender.setHost(mailHost);
		mailSender.setPort(465); // SSL Port
		mailSender.setUsername(mailUsername);
		mailSender.setPassword(mailPassword);
		Properties props = mailSender.getJavaMailProperties();
		props.put("mail.smtp.auth", "true");
		props.put("mail.smtp.ssl.enable", "true"); // SSL for port 465
		props.put("mail.smtp.starttls.enable", "true"); // optional if server supports TLS
		props.put("mail.transport.protocol", "smtp");
		props.put("mail.debug", "false");

		mailSender.setDefaultEncoding("UTF-8");

		return mailSender;
	}
	
}
