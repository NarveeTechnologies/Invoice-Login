package com.invoice.config;

import java.util.Properties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Configuration
public class MailConfig {

    @Bean
    public JavaMailSender javaMailSender() {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();

        mailSender.setHost("host.narveetech.com");
        mailSender.setPort(465); // SSL Port
        mailSender.setUsername("no-reply@narvee.com");
        mailSender.setPassword("4hi;lMs[,Yu66Zql");

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.enable", "true"); // SSL for port 465
        props.put("mail.smtp.starttls.enable", "true"); // optional if server supports TLS
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.debug", "true"); // helpful for debugging

        mailSender.setDefaultEncoding("UTF-8");

        return mailSender;
    }
}

