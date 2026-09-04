package com.invoice.otp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Derives the stored forms of a passcode and an identifier.
 *
 * <p>Both are HMAC-SHA256 under a server-held pepper that lives in the
 * environment and never in the database. That choice is deliberate over a
 * password hash such as bcrypt. A six-character code is drawn from under 2^30
 * possibilities, so an attacker holding the table can enumerate the entire
 * space against a bcrypt digest given enough time, and the work factor only
 * decides how much. Without the pepper the same attacker cannot test a single
 * candidate, because the MAC key is not in the data they stole. The compromise
 * that matters here is database disclosure, and peppering is what addresses it.
 *
 * <p>The code MAC covers {@code purpose|identifier|code} rather than the code
 * alone. A row lifted from the table therefore cannot be replayed against a
 * different address or a different flow even when the underlying code repeats,
 * and the purpose separation survives an attacker with write access to
 * {@code purpose}: changing the column no longer matches the stored MAC.
 */
@Component
public class OtpHasher {

	private static final String ALGORITHM = "HmacSHA256";

	private final SecretKeySpec key;

	public OtpHasher(OtpProperties properties) {
		this.key = new SecretKeySpec(
				properties.getPepper().getBytes(StandardCharsets.UTF_8), ALGORITHM);
	}

	/**
	 * Normalises an email address for use as a stable lookup key: trimmed and
	 * lower-cased in {@link Locale#ROOT}, so that a Turkish-locale JVM does not
	 * map {@code I} to a dotless lowercase and silently key the same address two
	 * different ways.
	 */
	public static String normaliseIdentifier(String identifier) {
		return identifier == null ? "" : identifier.trim().toLowerCase(Locale.ROOT);
	}

	/** Lookup key for an identifier. The address itself is never stored. */
	public String hashIdentifier(String identifier) {
		return hex(mac("id:" + normaliseIdentifier(identifier)));
	}

	/** Stored form of a passcode, bound to its purpose and identifier. */
	public String hashCode(OtpPurpose purpose, String identifier, String code) {
		return hex(mac("code:" + purpose.name() + '|' + normaliseIdentifier(identifier) + '|' + code));
	}

	/**
	 * One-way form of an IP address or user agent, for rate limiting and audit
	 * without retaining the value itself.
	 */
	public String hashOpaque(String value) {
		return value == null || value.isBlank() ? null : hex(mac("opaque:" + value));
	}

	/**
	 * Constant-time comparison of two hex digests. Both are the same length by
	 * construction, so this leaks nothing through timing;
	 * {@code String.equals} would return early at the first differing character.
	 */
	public static boolean matches(String expectedHex, String actualHex) {
		if (expectedHex == null || actualHex == null) {
			return false;
		}
		return MessageDigest.isEqual(
				expectedHex.getBytes(StandardCharsets.UTF_8),
				actualHex.getBytes(StandardCharsets.UTF_8));
	}

	private byte[] mac(String message) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(key);
			return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
			// HmacSHA256 is required of every JVM, and the key is validated at
			// startup, so neither branch is reachable in a running service.
			throw new IllegalStateException("OTP hashing unavailable", e);
		}
	}

	private static String hex(byte[] bytes) {
		return HexFormat.of().formatHex(bytes);
	}
}
