package com.invoice.otp;

/**
 * The parts of the HTTP request the OTP layer is allowed to see.
 *
 * <p>Passed in rather than pulled from a thread-local so the service stays
 * testable and so nothing in {@code com.invoice.otp} needs a servlet import.
 * Both values are hashed before they are stored.
 */
public record OtpRequestContext(String ipAddress, String userAgent) {

	public static OtpRequestContext none() {
		return new OtpRequestContext(null, null);
	}
}
