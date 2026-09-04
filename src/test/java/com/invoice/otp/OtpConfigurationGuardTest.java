package com.invoice.otp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.invoice.mail.EmailNotificationService;
import com.invoice.mail.MailFromProperties;

/**
 * Every case here is a configuration that would otherwise start cleanly and
 * then fail invisibly: passcodes minted that nobody receives, or stored under a
 * key weak enough to brute-force.
 */
class OtpConfigurationGuardTest {

	private static OtpProperties validProperties() {
		OtpProperties p = new OtpProperties();
		p.setPepper("a-valid-pepper-of-at-least-32-bytes-long");
		return p;
	}

	private static MailFromProperties validFrom() {
		MailFromProperties f = new MailFromProperties();
		f.setAddress("no-reply@example.com");
		return f;
	}

	private static EmailNotificationService mail(String name, boolean real) {
		EmailNotificationService m = mock(EmailNotificationService.class);
		when(m.providerName()).thenReturn(name);
		when(m.providerDeliversForReal()).thenReturn(real);
		return m;
	}

	private static OtpConfigurationGuard guard(OtpProperties otp, MailFromProperties from,
			EmailNotificationService mail, String... profiles) {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles(profiles);
		return new OtpConfigurationGuard(otp, from, mail, env);
	}

	@Test
	@DisplayName("a complete production configuration starts")
	void validConfigurationPasses() {
		assertDoesNotThrow(() -> guard(validProperties(), validFrom(),
				mail("smtp", true), "prod").validate());
	}

	@Test
	@DisplayName("a missing pepper stops startup")
	void missingPepperFails() {
		OtpProperties p = validProperties();
		p.setPepper(null);
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> guard(p, validFrom(), mail("smtp", true), "prod").validate());
		assertTrue(e.getMessage().contains("OTP_PEPPER"));
	}

	@Test
	@DisplayName("an unresolved placeholder is caught rather than used as a literal")
	void placeholderPepperFails() {
		// Spring passes ${OTP_PEPPER} through verbatim when the variable is
		// unset, so it arrives looking like a perfectly good value.
		OtpProperties p = validProperties();
		p.setPepper("${OTP_PEPPER}");
		assertThrows(IllegalStateException.class,
				() -> guard(p, validFrom(), mail("smtp", true), "prod").validate());
	}

	@Test
	@DisplayName("a short pepper stops startup")
	void shortPepperFails() {
		OtpProperties p = validProperties();
		p.setPepper("too-short");
		assertThrows(IllegalStateException.class,
				() -> guard(p, validFrom(), mail("smtp", true), "prod").validate());
	}

	@Test
	@DisplayName("production refuses a provider that does not deliver")
	void loggingProviderRefusedInProduction() {
		// The failure this whole design exists to prevent.
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> guard(validProperties(), validFrom(), mail("logging", false), "prod")
						.validate());
		assertTrue(e.getMessage().contains("could ever receive"));
	}

	@Test
	@DisplayName("no active profile is treated as production, not as development")
	void noProfileIsTreatedAsProduction() {
		// Fail closed: a profile nobody set must not be the permissive case.
		assertThrows(IllegalStateException.class,
				() -> guard(validProperties(), validFrom(), mail("logging", false)).validate());
	}

	@Test
	@DisplayName("an unrecognised profile is treated as production")
	void unknownProfileIsTreatedAsProduction() {
		assertThrows(IllegalStateException.class,
				() -> guard(validProperties(), validFrom(), mail("logging", false), "uatl")
						.validate());
	}

	@Test
	@DisplayName("dev may run without real delivery")
	void devAllowsLoggingProvider() {
		assertDoesNotThrow(() -> guard(validProperties(), validFrom(),
				mail("logging", false), "dev").validate());
	}

	@Test
	@DisplayName("a missing sender address stops startup")
	void missingSenderFails() {
		MailFromProperties f = new MailFromProperties();
		f.setAddress("  ");
		assertThrows(IllegalStateException.class,
				() -> guard(validProperties(), f, mail("smtp", true), "prod").validate());
	}

	@Test
	@DisplayName("implausible limits stop startup")
	void implausibleLimitsFail() {
		assertAll(
				() -> {
					OtpProperties p = validProperties();
					p.setTtl(java.time.Duration.ofHours(8));
					assertThrows(IllegalStateException.class, () -> guard(p, validFrom(),
							mail("smtp", true), "prod").validate(),
							"a passcode valid for 8 hours is a standing credential");
				},
				() -> {
					OtpProperties p = validProperties();
					p.setMaxAttempts(500);
					assertThrows(IllegalStateException.class, () -> guard(p, validFrom(),
							mail("smtp", true), "prod").validate(),
							"500 guesses makes brute force the cheaper attack");
				},
				() -> {
					OtpProperties p = validProperties();
					p.setRetention(java.time.Duration.ofHours(1));
					assertThrows(IllegalStateException.class, () -> guard(p, validFrom(),
							mail("smtp", true), "prod").validate(),
							"sweeping inside the rate-limit window lifts the ceilings");
				});
	}
}
