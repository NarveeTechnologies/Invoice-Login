package com.invoice.otp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The generator replaced one built on {@code java.util.Random} whose output was
 * predictable from two observed values, and which fixed the shape at three
 * letters and three digits.
 */
class OtpCodeGeneratorTest {

	private final OtpCodeGenerator generator = new OtpCodeGenerator();

	@Test
	@DisplayName("codes are six characters from the declared alphabet")
	void shapeIsCorrect() {
		for (int i = 0; i < 500; i++) {
			String code = generator.generate();
			assertEquals(6, code.length(), "length is part of the client contract");
			for (char c : code.toCharArray()) {
				assertTrue(OtpCodeGenerator.ALPHABET.indexOf(c) >= 0,
						"character " + c + " is outside the alphabet");
			}
		}
	}

	@Test
	@DisplayName("confusable characters never appear")
	void confusablesExcluded() {
		// Both halves of each pair are excluded. Excluding only one would leave
		// a user who reads O and types 0 wrong either way.
		for (int i = 0; i < 500; i++) {
			String code = generator.generate();
			for (char c : "01ILO".toCharArray()) {
				assertEquals(-1, code.indexOf(c),
						"confusable character " + c + " appeared in " + code);
			}
		}
	}

	@Test
	@DisplayName("the shape is not fixed at three letters and three digits")
	void noPositionalStructure() {
		// The old generator emitted exactly three of each, every time, which
		// told an attacker the digit positions for free. Over this many draws a
		// uniform generator must produce at least one code with a different mix.
		boolean sawOtherThanThreeThree = false;
		for (int i = 0; i < 500 && !sawOtherThanThreeThree; i++) {
			long digits = generator.generate().chars().filter(Character::isDigit).count();
			if (digits != 3) {
				sawOtherThanThreeThree = true;
			}
		}
		assertTrue(sawOtherThanThreeThree,
				"every code had exactly three digits — the old fixed shape is back");
	}

	@Test
	@DisplayName("output does not repeat over a large sample")
	void doesNotRepeat() {
		Set<String> seen = new HashSet<>();
		int draws = 2000;
		for (int i = 0; i < draws; i++) {
			seen.add(generator.generate());
		}
		// 31^6 is ~887 million, so collisions in 2000 draws are vanishingly
		// unlikely. A generator stuck in a short cycle fails here loudly.
		assertTrue(seen.size() > draws * 0.99,
				"only " + seen.size() + " distinct codes in " + draws + " draws");
	}

	@Test
	@DisplayName("the configured length is honoured")
	void configuredLengthIsUsed() {
		OtpProperties eight = new OtpProperties();
		eight.setLength(8);
		OtpCodeGenerator longer = new OtpCodeGenerator(eight);

		assertEquals(8, longer.length());
		for (int i = 0; i < 100; i++) {
			assertEquals(8, longer.generate().length());
		}
		// And validation follows the same setting, rather than a constant.
		assertTrue(longer.isWellFormed("ABCD2345"));
		assertFalse(longer.isWellFormed("ABC234"), "the 6-char default must not pass at length 8");
	}

	@Test
	@DisplayName("malformed submissions are rejected before any database work")
	void wellFormedRejectsJunk() {
		assertAll(
				() -> assertFalse(generator.isWellFormed(null)),
				() -> assertFalse(generator.isWellFormed("")),
				() -> assertFalse(generator.isWellFormed("ABC12"), "too short"),
				() -> assertFalse(generator.isWellFormed("ABC1234"), "too long"),
				() -> assertFalse(generator.isWellFormed("ABC12!"), "punctuation"),
				() -> assertFalse(generator.isWellFormed("ABC120"), "excluded digit 0"),
				() -> assertFalse(generator.isWellFormed("abc234"), "lower case"),
				() -> assertTrue(generator.isWellFormed("ABC234")));
	}
}
