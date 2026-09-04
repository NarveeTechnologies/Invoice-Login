package com.invoice.otp;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/**
 * Generates passcodes from {@link SecureRandom}.
 *
 * <p>The previous generator was not usable as a security token. It drew from
 * {@code java.util.Random} — a 48-bit linear congruential generator whose whole
 * future output follows from two observed values — and then reshuffled with
 * {@code Collections.shuffle}, which reaches for a second unseeded
 * {@code Random}. It also fixed the shape at exactly three uppercase letters
 * and three digits, which costs more than it looks: constraining the multiset
 * cuts the space from 36^6 to C(6,3)x26^3x10^3, a little over 351 million, and
 * hands an attacker the positions of the digits for free.
 *
 * <p>This draws every character uniformly from one alphabet with
 * {@code SecureRandom.nextInt(bound)}, which is rejection-sampled and so free
 * of the modulo bias {@code nextInt() % n} would introduce.
 *
 * <p>The alphabet omits {@code 0 1 I L O}. Excluding both halves of each
 * confusable pair is what makes the exclusion safe: a user who reads O and
 * types 0 is wrong either way if only one is absent, whereas here neither
 * character can occur, so the ambiguity never arises. That leaves 31 symbols
 * and 31^6 = 887,503,681 codes — about 2.5x the old space, uniform, and with
 * no positional structure to leak.
 */
@Component
public class OtpCodeGenerator {

	/** 8 digits + 23 letters. 0, 1, I, L and O are excluded as confusable. */
	static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

	/** Default when nothing is configured; the clients validate against this. */
	static final int DEFAULT_LENGTH = 6;

	private final SecureRandom random = new SecureRandom();
	private final int length;

	public OtpCodeGenerator(OtpProperties properties) {
		this.length = properties.getLength();
	}

	/** Test convenience: the default length. */
	OtpCodeGenerator() {
		this.length = DEFAULT_LENGTH;
	}

	public int length() {
		return length;
	}

	/**
	 * @return a fresh passcode. Never logged, never persisted in this form.
	 */
	public String generate() {
		StringBuilder code = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
		}
		return code.toString();
	}

	/**
	 * Whether a submitted string could be one of our codes. Applied before any
	 * database work so that malformed input costs nothing and, more importantly,
	 * consumes no attempt against the challenge.
	 */
	public boolean isWellFormed(String candidate) {
		if (candidate == null || candidate.length() != length) {
			return false;
		}
		for (int i = 0; i < candidate.length(); i++) {
			if (ALPHABET.indexOf(candidate.charAt(i)) < 0) {
				return false;
			}
		}
		return true;
	}
}
