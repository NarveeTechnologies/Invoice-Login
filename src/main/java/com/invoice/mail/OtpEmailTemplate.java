package com.invoice.mail;

import java.time.Duration;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import com.invoice.otp.OtpPurpose;

/**
 * Composes the Invoice-branded passcode email in both plain text and HTML.
 *
 * <p>Nothing in either body is a link or a token. A passcode delivered as a
 * clickable URL is a passcode in browser history, in referer headers and in
 * any mail scanner's logs, so the recipient is asked to type it instead.
 */
@Component
public class OtpEmailTemplate {

	public EmailMessage compose(String recipient, OtpPurpose purpose, String code, Duration ttl) {
		String action = actionFor(purpose);
		long minutes = Math.max(1, ttl.toMinutes());

		return new EmailMessage(
				recipient,
				subjectFor(purpose),
				textBody(action, code, minutes),
				htmlBody(action, code, minutes));
	}

	private static String subjectFor(OtpPurpose purpose) {
		return switch (purpose) {
			case LOGIN -> "Your Invoice sign-in code";
			case REGISTRATION -> "Your Invoice verification code";
			case ACCOUNT_NUMBER_CHANGE -> "Confirm your Invoice bank detail change";
			case PASSWORD_RESET -> "Your Invoice password reset code";
		};
	}

	private static String actionFor(OtpPurpose purpose) {
		return switch (purpose) {
			case LOGIN -> "sign in to Invoice";
			case REGISTRATION -> "verify your email address";
			case ACCOUNT_NUMBER_CHANGE -> "confirm a change to your bank details";
			case PASSWORD_RESET -> "reset your Invoice password";
		};
	}

	private static String textBody(String action, String code, long minutes) {
		return """
				Invoice

				Use this code to %s:

				    %s

				The code expires in %d minutes and can be used only once.

				Never share it with anyone. Invoice staff will not ask you for it.

				If you did not request this code, you can safely ignore this email —
				no action has been taken on your account. If you receive these
				repeatedly and did not ask for them, contact your administrator.

				— Invoice
				This is an automated message. Please do not reply.
				"""
				.formatted(action, code, minutes);
	}

	private static String htmlBody(String action, String code, long minutes) {
		// The code is generated from a fixed 31-character alphabet and the other
		// two values are numbers and internal strings, so none of it can carry
		// markup. Escaped regardless: the cost is nil and it means a future
		// caller passing user-supplied text cannot turn this into an injection.
		String safeAction = HtmlUtils.htmlEscape(action);
		String safeCode = HtmlUtils.htmlEscape(code);

		return """
				<!DOCTYPE html>
				<html lang="en">
				<head>
				  <meta charset="UTF-8">
				  <meta name="viewport" content="width=device-width, initial-scale=1">
				  <title>Invoice verification code</title>
				</head>
				<body style="margin:0;padding:0;background:#f4f6f9;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;">
				  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f4f6f9;padding:24px 12px;">
				    <tr><td align="center">
				      <table role="presentation" width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%%;background:#ffffff;border-radius:8px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.08);">
				        <tr>
				          <td style="background:#2563eb;padding:20px 24px;text-align:center;">
				            <span style="color:#ffffff;font-size:20px;font-weight:600;letter-spacing:0.5px;">Invoice</span>
				          </td>
				        </tr>
				        <tr>
				          <td style="padding:32px 24px;">
				            <p style="margin:0 0 20px;font-size:15px;color:#374151;">
				              Use this code to %s:
				            </p>
				            <div style="margin:0 0 20px;padding:18px;text-align:center;background:#f3f4f6;border:1px solid #e5e7eb;border-radius:6px;">
				              <span style="font-family:'SFMono-Regular',Consolas,monospace;font-size:32px;font-weight:700;letter-spacing:8px;color:#111827;">%s</span>
				            </div>
				            <p style="margin:0 0 12px;font-size:14px;color:#374151;">
				              The code expires in <strong>%d minutes</strong> and can be used only once.
				            </p>
				            <p style="margin:0 0 12px;font-size:14px;color:#b91c1c;">
				              Never share it with anyone. Invoice staff will not ask you for it.
				            </p>
				            <p style="margin:0;font-size:13px;color:#6b7280;">
				              If you did not request this code you can safely ignore this email —
				              no action has been taken on your account. If these keep arriving and
				              you did not ask for them, contact your administrator.
				            </p>
				          </td>
				        </tr>
				        <tr>
				          <td style="background:#f3f4f6;padding:16px 24px;text-align:center;font-size:12px;color:#6b7280;">
				            This is an automated message. Please do not reply.
				          </td>
				        </tr>
				      </table>
				    </td></tr>
				  </table>
				</body>
				</html>
				"""
				.formatted(safeAction, safeCode, minutes);
	}
}
