package com.invoice.serviceImpl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;

import com.invoice.service.EmailService;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class EmailServiceImpl implements EmailService {

	@Autowired
	private JavaMailSender mailSender;

	@Value("${spring.mail.username}")
	private String fromEmail;

	@Value("${app.frontend.url}")
	private String loginUrl;

	@Async
	@Override
	public void sendRegistrationEmail(String toEmail, String fullName, String roleName) {
		try {
			MimeMessage message = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(message, true);

			helper.setFrom(fromEmail);
			helper.setTo(toEmail);
			helper.setSubject("Registration Successful - Invoicing Application");

			StringBuilder mailContent = new StringBuilder();

			mailContent.append("<!DOCTYPE html>");
			mailContent.append("<html>");
			mailContent.append("<head><meta charset='UTF-8'></head>");
			mailContent.append(
					"<body style='margin:0; padding:0; font-family: Arial, sans-serif; background-color:#f4f6f9;'>");

			// Main Container
			mailContent.append("<table align='center' width='600' cellpadding='0' cellspacing='0' ");
			mailContent.append("style='background:#ffffff; margin-top:40px; border-radius:8px; ");
			mailContent.append("overflow:hidden; box-shadow:0 4px 12px rgba(0,0,0,0.1);'>");

			// Header
			mailContent.append("<tr>");
			mailContent.append("<td style='background:#2563eb; padding:20px; text-align:center;'>");
			mailContent.append("<h2 style='color:#ffffff; margin:0;'>  Invoice  </h2>");
			mailContent.append("</td>");
			mailContent.append("</tr>");

			// Body
			mailContent.append("<tr>");
			mailContent.append("<td style='padding:30px;'>");

			mailContent.append("<h3 style='margin-top:0; color:#1f2937;'>Invoicing Team</h3>");

			mailContent.append("<p style='font-size:15px; color:#374151;'>");
			mailContent.append("Hello <strong>" + fullName + "</strong>,</p>");

			mailContent.append("<p style='font-size:15px; color:#374151;'>");
			mailContent.append("Your account role has been successfully created and assigned by the administrator.");
			mailContent.append("</p>");

			mailContent.append("<p style='font-size:15px; color:#374151;'>");
			mailContent.append("Assigned Role: <strong style='color:#2563eb;'>" + roleName + "</strong>");
			mailContent.append("</p>");

			mailContent.append("<p style='font-size:15px; color:#374151;'>");
			mailContent.append("You can now log in to your account to access features based on your assigned role.");
			mailContent.append("</p>");

			// Login Button (Using passed loginUrl ✅)
			mailContent.append("<div style='text-align:center; margin:35px 0;'>");
			mailContent.append("<a href='" + loginUrl + "' ");
			mailContent.append("style='background-color:#2563eb; color:#ffffff; ");
			mailContent.append("padding:14px 34px; border-radius:6px; ");
			mailContent.append("text-decoration:none; font-size:16px; ");
			mailContent.append("font-weight:600; display:inline-block;'>");
			mailContent.append("Login to Your Account");
			mailContent.append("</a>");
			mailContent.append("</div>");

			mailContent.append("<p style='font-size:14px; color:#6b7280;'>");
			mailContent.append("If you have any questions, please contact the system administrator.");
			mailContent.append("</p>");

			mailContent.append("<p style='font-size:14px; color:#374151;'>");
			mailContent.append("Best Regards,<br><strong>Invoicing Team</strong>");
			mailContent.append("</p>");

			mailContent.append("</td>");
			mailContent.append("</tr>");

			// Footer
			mailContent.append("<tr>");
			mailContent.append(
					"<td style='background:#f3f4f6; text-align:center; padding:15px; font-size:13px; color:#6b7280;'>");
			mailContent.append("© 2026 Invoicing Application. All rights reserved.");
			mailContent.append("</td>");
			mailContent.append("</tr>");

			mailContent.append("</table>");
			mailContent.append("</body>");
			mailContent.append("</html>");

			helper.setText(mailContent.toString(), true); // ✅ Correct variable
			mailSender.send(message);

		} catch (Exception e) {
			log.error("Failed to send registration email to {}", toEmail, e);
		}
	}

}
