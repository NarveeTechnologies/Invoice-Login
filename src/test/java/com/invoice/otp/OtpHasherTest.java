package com.invoice.otp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OtpHasherTest {

	private static OtpHasher hasherWith(String pepper) {
		OtpProperties p = new OtpProperties();
		p.setPepper(pepper);
		return new OtpHasher(p);
	}

	private final OtpHasher hasher = hasherWith("test-pepper-at-least-32-bytes-long-000000");

	@Test
	@DisplayName("the stored form is not the code")
	void hashIsNotPlaintext() {
		String hash = hasher.hashCode(OtpPurpose.LOGIN, "user@example.com", "ABC234");
		assertNotEquals("ABC234", hash);
		assertFalse(hash.contains("ABC234"));
		assertEquals(64, hash.length(), "SHA-256 as hex");
	}

	@Test
	@DisplayName("the same code under a different purpose hashes differently")
	void purposeIsBoundIntoTheMac() {
		// This is what stops a row lifted from the table being replayed against
		// another flow, and what stops an attacker with write access flipping
		// the purpose column.
		String login = hasher.hashCode(OtpPurpose.LOGIN, "user@example.com", "ABC234");
		String register = hasher.hashCode(OtpPurpose.REGISTRATION, "user@example.com", "ABC234");
		assertNotEquals(login, register);
	}

	@Test
	@DisplayName("the same code for a different address hashes differently")
	void identifierIsBoundIntoTheMac() {
		String a = hasher.hashCode(OtpPurpose.LOGIN, "a@example.com", "ABC234");
		String b = hasher.hashCode(OtpPurpose.LOGIN, "b@example.com", "ABC234");
		assertNotEquals(a, b);
	}

	@Test
	@DisplayName("without the pepper the same input produces a different hash")
	void pepperChangesEverything() {
		// The property that makes offline brute force impossible: an attacker
		// holding the table but not the environment cannot test a candidate.
		OtpHasher other = hasherWith("a-completely-different-pepper-32-bytes-xx");
		assertNotEquals(
				hasher.hashCode(OtpPurpose.LOGIN, "user@example.com", "ABC234"),
				other.hashCode(OtpPurpose.LOGIN, "user@example.com", "ABC234"));
	}

	@Test
	@DisplayName("identifiers normalise case and whitespace consistently")
	void identifierNormalisation() {
		String canonical = hasher.hashIdentifier("user@example.com");
		assertAll(
				() -> assertEquals(canonical, hasher.hashIdentifier("  user@example.com  ")),
				() -> assertEquals(canonical, hasher.hashIdentifier("USER@EXAMPLE.COM")),
				() -> assertEquals(canonical, hasher.hashIdentifier("User@Example.Com")));
	}

	@Test
	@DisplayName("normalisation does not depend on the JVM default locale")
	void normalisationIsLocaleIndependent() {
		// Turkish lower-cases I to a dotless i. Under the default locale that
		// would key the same address two different ways depending on where the
		// container happened to be configured.
		java.util.Locale original = java.util.Locale.getDefault();
		try {
			java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));
			assertEquals("iii@example.com", OtpHasher.normaliseIdentifier("III@example.com"));
		} finally {
			java.util.Locale.setDefault(original);
		}
	}

	@Test
	@DisplayName("comparison is constant time and still correct")
	void constantTimeComparison() {
		String h = hasher.hashCode(OtpPurpose.LOGIN, "user@example.com", "ABC234");
		assertAll(
				() -> assertTrue(OtpHasher.matches(h, h)),
				() -> assertFalse(OtpHasher.matches(h, hasher.hashCode(
						OtpPurpose.LOGIN, "user@example.com", "ABC235"))),
				() -> assertFalse(OtpHasher.matches(h, null)),
				() -> assertFalse(OtpHasher.matches(null, h)));
	}

	@Test
	@DisplayName("a null or blank opaque value hashes to null rather than to a constant")
	void opaqueNullHandling() {
		// A constant hash for "no IP" would put every request without one into a
		// single rate-limit bucket and throttle unrelated users together.
		assertNull(hasher.hashOpaque(null));
		assertNull(hasher.hashOpaque("   "));
		assertNotNull(hasher.hashOpaque("203.0.113.7"));
	}
}
