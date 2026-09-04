package com.invoice.performance;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import jakarta.persistence.EntityManager;

import org.hibernate.Session;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.invoice.entity.Privilege;
import com.invoice.entity.Role;
import com.invoice.repository.RoleRepository;

/**
 * Guards the role-listing endpoints against N+1 regression.
 *
 * <p>This exists because the fix for the lazy-loading failures created the
 * problem it now tests for. Marking the DTO mapping
 * {@code @Transactional(readOnly = true)} stopped
 * {@code LazyInitializationException} — and replaced it with one query per row,
 * because a transaction makes lazy loading *work*. Measured on the running
 * stack: 22 queries for 21 roles.
 *
 * <p>Counting statements is the only way to catch that. The endpoint returns
 * the right data either way; nothing fails, nothing throws, and the cost is
 * invisible until a table grows.
 */
@Testcontainers
@DataJpaTest(showSql = false)
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(
		replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
class RoleQueryCountIT {

	private static final int ROLES = 20;
	private static final int PRIVILEGES_PER_ROLE = 5;

	@Container
	@SuppressWarnings("resource")
	static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:16-alpine")
					.withDatabaseName("invoice")
					.withUsername("invoice")
					.withPassword("invoice");

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		POSTGRES.start();
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
		registry.add("spring.flyway.enabled", () -> "false");
		registry.add("app.flyway.after-jpa", () -> "false");
		// The whole point of this test.
		registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
	}

	@Autowired
	private RoleRepository roleRepository;

	@Autowired
	private EntityManager entityManager;

	private Statistics statistics() {
		return entityManager.unwrap(Session.class).getSessionFactory().getStatistics();
	}

	@BeforeEach
	void seed() {
		for (int r = 0; r < ROLES; r++) {
			Role role = new Role();
			role.setRoleName("ROLE_" + r);
			role.setStatus("ACTIVE");
			role.setAdminId(1L);
			java.util.Set<Privilege> privileges = new java.util.HashSet<>();
			for (int p = 0; p < PRIVILEGES_PER_ROLE; p++) {
				Privilege privilege = new Privilege();
				privilege.setName("PRIV_" + r + "_" + p);
				privilege.setCategory("CAT");
				entityManager.persist(privilege);
				privileges.add(privilege);
			}
			role.setPrivileges(privileges);
			entityManager.persist(role);
		}
		entityManager.flush();
		entityManager.clear();
	}

	@Test
	@DisplayName("listing all roles with their privileges costs one query, not one per role")
	void findAllWithPrivilegesIsASingleQuery() {
		statistics().clear();

		List<Role> roles = roleRepository.findAllWithPrivileges();
		// Touch the collection exactly as convertToDTO does.
		int touched = roles.stream().mapToInt(r -> r.getPrivileges().size()).sum();

		long queries = statistics().getPrepareStatementCount();

		assertEquals(ROLES, roles.size(), "seed did not produce the expected roles");
		assertEquals(ROLES * PRIVILEGES_PER_ROLE, touched,
				"privileges were not actually loaded, so the query count means nothing");
		assertEquals(1, queries,
				"listing " + ROLES + " roles took " + queries + " queries. A fetch join was "
						+ "removed, or the service went back to a plain findAll() — this is the "
						+ "N+1 that a read-only transaction hides rather than reports.");
	}

	@Test
	@DisplayName("listing one admin's roles is also a single query")
	void findByAdminIdWithPrivilegesIsASingleQuery() {
		statistics().clear();

		List<Role> roles = roleRepository.findByAdminIdWithPrivileges(1L);
		int touched = roles.stream().mapToInt(r -> r.getPrivileges().size()).sum();

		assertEquals(ROLES * PRIVILEGES_PER_ROLE, touched);
		assertEquals(1, statistics().getPrepareStatementCount(),
				"the per-admin listing regressed to N+1");
	}

	@Test
	@DisplayName("the unfetched finder is what N+1 looks like — the control for this test")
	void plainFindAllDemonstratesTheProblem() {
		// Proves the assertions above can fail: the same data through the plain
		// finder costs one query per role. If this ever drops to 1, the fetch
		// strategy changed globally and the guards above stopped meaning anything.
		statistics().clear();

		List<Role> roles = roleRepository.findAll();
		roles.forEach(r -> r.getPrivileges().size());

		long queries = statistics().getPrepareStatementCount();
		assertTrue(queries > ROLES,
				"expected roughly one query per role from the unfetched finder, got " + queries);
	}
}
