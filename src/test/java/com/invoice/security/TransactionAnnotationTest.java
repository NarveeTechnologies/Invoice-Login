package com.invoice.security;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import com.invoice.serviceImpl.PrivilegeServiceImpl;
import com.invoice.serviceImpl.RoleServiceImpl;

/**
 * No method that writes may be marked {@code @Transactional(readOnly = true)}.
 *
 * <p>This is here because it happened. Making the OSIV fix work required
 * {@code @Transactional(readOnly = true)} on the service methods that map
 * entities to DTOs, and those were annotated in bulk by matching return types.
 * {@code createRole} and {@code createPrivilege} return {@code RoleDTO} and
 * {@code PrivilegeDTO}, so they were caught by the same pattern — and role and
 * privilege creation broke completely:
 *
 * <pre>ERROR: cannot execute INSERT in a read-only transaction</pre>
 *
 * <p>Nothing detected it. The code compiled, every existing test passed, and the
 * endpoints answered 400/500 in a way that looked like input validation. It
 * surfaced only when a mass-assignment probe sent a *valid* body and the control
 * request failed identically to the malicious one — the giveaway was that both
 * failed, not that one did.
 *
 * <p>Reflection rather than an integration test: this asserts a property of the
 * annotations, needs no database, and runs in milliseconds, so it can guard
 * every method rather than the handful an integration test would reach.
 */
class TransactionAnnotationTest {

	/** Method names that write, by convention in this codebase. */
	private static final Pattern MUTATOR =
			Pattern.compile("^(create|save|update|delete|remove|assign|reset|provision|reprovision|deactivate).*");

	private static final Class<?>[] SERVICES = { RoleServiceImpl.class, PrivilegeServiceImpl.class };

	@Test
	@DisplayName("no mutating service method is marked read-only")
	void mutatorsAreNotReadOnly() {
		List<String> offenders = new ArrayList<>();

		for (Class<?> service : SERVICES) {
			for (Method method : service.getDeclaredMethods()) {
				if (!MUTATOR.matcher(method.getName()).matches()) {
					continue;
				}
				Transactional tx = method.getAnnotation(Transactional.class);
				if (tx != null && tx.readOnly()) {
					offenders.add(service.getSimpleName() + "." + method.getName());
				}
			}
		}

		assertTrue(offenders.isEmpty(),
				"these methods write but are marked @Transactional(readOnly = true), so every "
						+ "insert or update inside them fails with \"cannot execute INSERT in a "
						+ "read-only transaction\": " + offenders);
	}

	@Test
	@DisplayName("the mutator pattern actually matches the methods it is meant to")
	void patternIsNotVacuous() {
		// A guard that matches nothing passes forever. Prove it sees the real
		// method names before trusting the assertion above.
		List<String> matched = new ArrayList<>();
		for (Class<?> service : SERVICES) {
			for (Method method : service.getDeclaredMethods()) {
				if (MUTATOR.matcher(method.getName()).matches()) {
					matched.add(service.getSimpleName() + "." + method.getName());
				}
			}
		}
		assertTrue(matched.size() >= 4,
				"the mutator pattern matched only " + matched + " — it has stopped covering "
						+ "the write methods and the guard above is now vacuous");
	}
}
