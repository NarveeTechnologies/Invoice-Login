package com.invoice.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.invoice.entity.User;
import com.invoice.repository.UserRepository;
import com.invoice.serviceImpl.JwtServiceImpl;

/**
 * Cross-tenant authorization, asserted against a running application.
 *
 * <p>Three P0 defects were found by probing this service by hand, all the same
 * shape: the <em>list</em> queries filtered by tenant while the <em>by-id</em>
 * queries did not. A tenant administrator could read, rename and delete another
 * tenant's users, roles and privileges. Those fixes were verified manually and
 * nothing in the build protected them, so a refactor could have removed a guard
 * silently. This suite is that protection.
 *
 * <p>Every case asserts three things together, because any one alone is
 * misleading:
 * <ol>
 *   <li>the status code — a denial, not a leak;</li>
 *   <li>that the victim's <strong>marker values</strong> appear nowhere in the
 *       body, since a 403 with data in it is still a breach and a 404 with data
 *       in it is worse;</li>
 *   <li>that the database was <strong>not mutated</strong> — a write that is
 *       refused must also not have taken effect.</li>
 * </ol>
 *
 * <p>And every denial has a matching positive control. Confining everything to
 * "own object only" would pass a security test and break the product: the
 * manage-users screen legitimately loads a colleague inside the same tenant.
 *
 * <p><strong>Scope.</strong> Sessions are minted through the application's own
 * {@link JwtServiceImpl}, with the production {@code adminId} tenant claim. What
 * is under test is what the API does with a genuine session — not how the
 * session was obtained. OTP delivery is covered end-to-end against real SMTP
 * elsewhere; duplicating it here would make these tests slow and would not test
 * authorization any harder.
 */
@Testcontainers
@ActiveProfiles("authztest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CrossTenantAuthorizationIT {

	// --- tenant A: the authenticated caller -------------------------------
	private static final long TENANT_A = 1001L;
	private static final String A_ADMIN_EMAIL = "admin-a@tenant-a.example.com";
	private static final String A_COLLEAGUE_EMAIL = "colleague-a@tenant-a.example.com";
	private static final long A_ADMIN_MU = 1001L;
	private static final long A_COLLEAGUE_MU = 1002L;
	private static final long A_ROLE = 1001L;
	private static final long A_PRIVILEGE = 1001L;
	private static final String A_OWN_MARKER = "TENANT-1001-AUTH-SECRET";

	// --- tenant B: the victim ---------------------------------------------
	private static final long TENANT_B = 900L;
	private static final String B_EMAIL = "victim-b@tenant-b.example.com";
	private static final long B_MU = 900L;
	private static final long B_ROLE = 900L;
	private static final long B_PRIVILEGE = 9900L;
	private static final String B_ACCOUNT = "VICTIM-SECRET-ACCT-999";
	private static final String B_ROUTING = "VICTIM-ROUTING-999888777";
	private static final String B_TAX = "VICTIM-TAX-SECRET-900";
	private static final String B_ADDRESS = "VICTIM-ADDRESS-SECRET-900";
	private static final String B_NAME = "TENANT-900-VICTIM-SECRET";

	/** Every value that must never appear in a response to tenant A. */
	private static final String[] VICTIM_MARKERS =
			{ B_ACCOUNT, B_ROUTING, B_TAX, B_ADDRESS, B_NAME };

	@Container
	@SuppressWarnings("resource")
	static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:16-alpine")
					.withDatabaseName("invoice")
					.withUsername("invoice")
					.withPassword("invoice");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		POSTGRES.start();
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
	}

	@LocalServerPort
	int port;

	@Autowired
	TestRestTemplate rest;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	JwtServiceImpl jwtService;

	@Autowired
	UserRepository userRepository;

	private String tenantAToken;
	private String tenantBToken;

	@BeforeAll
	void seed() {
		jdbc.execute("SET search_path TO invoice");

		jdbc.update("INSERT INTO roles (roleid, role_name, status, admin_id) VALUES (?,?,?,?)",
				A_ROLE, "ADMIN", "ACTIVE", TENANT_A);
		jdbc.update("INSERT INTO roles (roleid, role_name, status, admin_id) VALUES (?,?,?,?)",
				B_ROLE, "TENANT_B_ROLE", "ACTIVE", TENANT_B);
		jdbc.update("INSERT INTO privileges (privilegeid, name, category, admin_id) VALUES (?,?,?,?)",
				A_PRIVILEGE, "TENANT_A_PRIV", "TENANT_A_CAT", TENANT_A);
		jdbc.update("INSERT INTO privileges (privilegeid, name, category, admin_id) VALUES (?,?,?,?)",
				B_PRIVILEGE, "TENANT_B_PRIV", "TENANT_B_CAT", TENANT_B);
		jdbc.update("INSERT INTO role_privileges (roleid, privilegeid) VALUES (?,?)", A_ROLE, A_PRIVILEGE);
		// A shared platform privilege: no owner. createPrivilege does not set
		// adminId, so the real catalogue looks like this and must stay visible.
		jdbc.update("INSERT INTO privileges (privilegeid, name, category, admin_id) VALUES (?,?,?,?)",
				7000L, "SHARED_PLATFORM_PRIV", "SHARED_CAT", null);

		insertUser(TENANT_A, A_ADMIN_EMAIL, "Adm", "A", A_ADMIN_MU, A_OWN_MARKER, "1001 Own Street");
		jdbc.update("INSERT INTO bank_details (id, user_id, bank_name, bank_account_number, routing_number) "
				+ "VALUES (?,?,?,?,?)", 1001, TENANT_A, "A Bank", A_OWN_MARKER, "1001-ROUTING");

		insertUser(1002L, A_COLLEAGUE_EMAIL, "Coll", "A", A_COLLEAGUE_MU, null, null);

		insertUser(TENANT_B, B_EMAIL, "Vic", "B", B_MU, B_TAX, B_ADDRESS);
		jdbc.update("INSERT INTO bank_details (id, user_id, bank_name, bank_account_number, routing_number) "
				+ "VALUES (?,?,?,?,?)", 900, TENANT_B, "B Bank", B_ACCOUNT, B_ROUTING);
		jdbc.update("UPDATE manage_users SET full_name = ? WHERE id = ?", B_NAME, B_MU);

		tenantAToken = mintTokenFor(A_ADMIN_EMAIL, TENANT_A);
		assertNotNull(tenantAToken, "could not mint a session for tenant A");
		// A second, real session is needed to prove that what tenant A creates
		// does not appear in tenant B's own catalogue. Asserting on admin_id
		// alone would only show the column, not what B can actually see.
		tenantBToken = mintTokenFor(B_EMAIL, TENANT_B);
		assertNotNull(tenantBToken, "could not mint a session for tenant B");
	}

	private void insertUser(long userId, String email, String first, String last,
			long manageUserId, String taxId, String address) {
		jdbc.update("INSERT INTO user_info (id, email, primary_email, first_name, last_name, full_name, "
				+ "active, roleid, designation, tax_id, address) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
				userId, email, email, first, last, first + " " + last, true,
				userId == TENANT_B ? B_ROLE : A_ROLE, "Staff", taxId, address);
		jdbc.update("INSERT INTO manage_users (id, email, full_name, company_domain, created_at, "
				+ "first_name, last_name, roleid, role_name, admin_id) "
				+ "VALUES (?,?,?,?,now(),?,?,?,?,?)",
				manageUserId, email, first + " " + last, "",
				first, last, userId == TENANT_B ? B_ROLE : A_ROLE,
				userId == TENANT_B ? "TENANT_B_ROLE" : "ADMIN",
				userId == TENANT_B ? TENANT_B : TENANT_A);
	}

	/**
	 * Mints a session with the application's own signer, carrying the
	 * production {@code adminId} tenant claim.
	 */
	private String mintTokenFor(String email, Long tenantAdminId) {
		User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
		return jwtService.generateToken(user, tenantAdminId, "ADMIN", Set.of("DELETE_MANAGE_USERS"));
	}

	// ---- request helpers --------------------------------------------------

	private ResponseEntity<String> call(HttpMethod method, String path, String token, String body) {
		HttpHeaders headers = new HttpHeaders();
		if (token != null) {
			headers.setBearerAuth(token);
		}
		headers.add("Content-Type", "application/json");
		return rest.exchange("http://localhost:" + port + path, method,
				new HttpEntity<>(body, headers), String.class);
	}

	private ResponseEntity<String> asTenantA(HttpMethod method, String path) {
		return call(method, path, tenantAToken, null);
	}

	private ResponseEntity<String> asTenantB(HttpMethod method, String path) {
		return call(method, path, tenantBToken, null);
	}

	private ResponseEntity<String> asTenantA(HttpMethod method, String path, String body) {
		return call(method, path, tenantAToken, body);
	}

	/** Denied, and no trace of the victim's data in the body. */
	private void assertDeniedWithoutLeaking(ResponseEntity<String> response, String what) {
		assertTrue(response.getStatusCode().is4xxClientError(),
				what + " must be refused, got " + response.getStatusCode());
		String body = response.getBody() == null ? "" : response.getBody();
		for (String marker : VICTIM_MARKERS) {
			assertFalse(body.contains(marker),
					what + " leaked the victim marker " + marker
							+ " — the status code is not the whole test");
		}
	}

	private long count(String sql, Object... args) {
		Long n = jdbc.queryForObject(sql, Long.class, args);
		return n == null ? 0 : n;
	}

	private String scalar(String sql, Object... args) {
		return jdbc.queryForObject(sql, String.class, args);
	}

	// ======================================================================
	@Nested
	@DisplayName("T-2  manage-users by id")
	class ManageUsers {

		@Test
		@DisplayName("cross-tenant read is refused and leaks nothing")
		void crossTenantReadRefused() {
			assertDeniedWithoutLeaking(
					asTenantA(HttpMethod.GET, "/auth/manageusers/" + B_MU),
					"GET /auth/manageusers/{other tenant}");
		}

		@Test
		@DisplayName("cross-tenant update is refused AND does not mutate the row")
		void crossTenantUpdateRefused() {
			assertDeniedWithoutLeaking(
					asTenantA(HttpMethod.PUT, "/auth/manageusers/" + B_MU,
							"{\"fullName\":\"OVERWRITTEN_BY_TENANT_A\"}"),
					"PUT /auth/manageusers/{other tenant}");
			assertEquals(B_NAME, scalar("SELECT full_name FROM manage_users WHERE id = ?", B_MU),
					"the refused update still changed the row");
		}

		@Test
		@DisplayName("cross-tenant delete is refused AND the row survives")
		void crossTenantDeleteRefused() {
			assertDeniedWithoutLeaking(
					asTenantA(HttpMethod.DELETE, "/auth/manageusers/" + B_MU),
					"DELETE /auth/manageusers/{other tenant}");
			assertEquals(1, count("SELECT count(*) FROM manage_users WHERE id = ?", B_MU),
					"the refused delete still removed the row");
		}

		@Test
		@DisplayName("own record is readable")
		void ownRecordReadable() {
			assertEquals(HttpStatus.OK,
					asTenantA(HttpMethod.GET, "/auth/manageusers/" + A_ADMIN_MU).getStatusCode());
		}

		@Test
		@DisplayName("same-tenant colleague is readable — administrative access is preserved")
		void sameTenantColleagueReadable() {
			// The manage-users dialog loads a colleague. Locking this down to
			// "own record only" would pass a security test and break the product.
			assertEquals(HttpStatus.OK,
					asTenantA(HttpMethod.GET, "/auth/manageusers/" + A_COLLEAGUE_MU).getStatusCode());
		}
	}

	// ======================================================================
	@Nested
	@DisplayName("T-1  roles by id")
	class Roles {

		@Test
		@DisplayName("cross-tenant read is refused and leaks nothing")
		void crossTenantReadRefused() {
			assertDeniedWithoutLeaking(asTenantA(HttpMethod.GET, "/auth/roles/" + B_ROLE),
					"GET /auth/roles/{other tenant}");
		}

		@Test
		@DisplayName("cross-tenant rename is refused AND the role keeps its name")
		void crossTenantRenameRefused() {
			assertDeniedWithoutLeaking(
					asTenantA(HttpMethod.PUT, "/auth/roles/" + B_ROLE,
							"{\"roleName\":\"HIJACKED_BY_TENANT_A\",\"status\":\"ACTIVE\"}"),
					"PUT /auth/roles/{other tenant}");
			assertEquals("TENANT_B_ROLE", scalar("SELECT role_name FROM roles WHERE roleid = ?", B_ROLE),
					"another tenant's role was renamed");
		}

		@Test
		@DisplayName("cross-tenant delete is refused AND the role survives")
		void crossTenantDeleteRefused() {
			// Roles carry privileges, so this is access-control mutation, not
			// merely disclosure.
			assertDeniedWithoutLeaking(asTenantA(HttpMethod.DELETE, "/auth/roles/" + B_ROLE),
					"DELETE /auth/roles/{other tenant}");
			assertEquals(1, count("SELECT count(*) FROM roles WHERE roleid = ?", B_ROLE),
					"another tenant's role was deleted");
		}

		@Test
		@DisplayName("own-tenant role is readable")
		void ownTenantRoleReadable() {
			assertEquals(HttpStatus.OK,
					asTenantA(HttpMethod.GET, "/auth/roles/" + A_ROLE).getStatusCode());
		}

		@Test
		@DisplayName("listing roles returns only the caller's tenant")
		void listingIsScoped() {
			ResponseEntity<String> response = asTenantA(HttpMethod.GET, "/auth/roles/getall");
			assertEquals(HttpStatus.OK, response.getStatusCode());
			assertFalse(String.valueOf(response.getBody()).contains("TENANT_B_ROLE"),
					"the role listing exposed another tenant's role");
		}
	}

	// ======================================================================
	@Nested
	@DisplayName("Admin profile and email check — previously unprobed")
	class AdminProfileAndEmailCheck {

		// These three were the last id-bearing endpoints without a
		// foreign-tenant probe. All three turned out to be correctly guarded
		// already; the tests exist so "already safe" is a verified statement
		// rather than an assumption, and so a future change breaks a test.

		@Test
		@DisplayName("GET /auth/updated/{id} refuses another tenant's profile")
		void crossTenantProfileReadRefused() {
			ResponseEntity<String> response = asTenantA(HttpMethod.GET, "/auth/updated/" + TENANT_B);
			assertTrue(response.getStatusCode().is4xxClientError(),
					"another tenant's admin profile answered " + response.getStatusCode());
			for (String marker : VICTIM_MARKERS) {
				assertFalse(response.getBody() != null && response.getBody().contains(marker),
						"the refusal leaked " + marker);
			}
		}

		@Test
		@DisplayName("PUT /auth/updated/{id} refuses, and changes nothing")
		void crossTenantProfileUpdateRefused() {
			ResponseEntity<String> response = asTenantA(HttpMethod.PUT, "/auth/updated/" + TENANT_B,
					"{\"companyName\":\"HIJACKED-20260904\"}");
			assertTrue(response.getStatusCode().is4xxClientError());
			assertEquals(0, count("SELECT count(*) FROM user_info WHERE company_name = ?",
					"HIJACKED-20260904"), "another tenant's profile was modified");
		}

		@Test
		@DisplayName("DELETE /auth/deleted/{id} refuses, and the tenant survives")
		void crossTenantProfileDeleteRefused() {
			ResponseEntity<String> response = asTenantA(HttpMethod.DELETE, "/auth/deleted/" + TENANT_B);
			assertTrue(response.getStatusCode().is4xxClientError());
			assertEquals(1, count("SELECT count(*) FROM user_info WHERE id = ?", TENANT_B),
					"another tenant's user record was deleted");
		}

		@Test
		@DisplayName("the refusal does not name the mechanism or echo the ids")
		void refusalNamesNoMechanism() {
			// It used to return ex.getMessage() verbatim:
			// "resource adminId=X does not match authenticated adminId=Y".
			String body = asTenantA(HttpMethod.GET, "/auth/updated/" + TENANT_B).getBody();
			assertFalse(body != null && body.contains("adminId"),
					"the refusal echoed the adminId comparison");
			assertFalse(body != null && body.contains("Cross-tenant"),
					"the refusal named the mechanism");
		}

		@Test
		@DisplayName("positive control: the caller can read its own admin profile")
		void ownProfileReadable() {
			assertEquals(HttpStatus.OK, asTenantA(HttpMethod.GET, "/auth/updated/" + TENANT_A).getStatusCode(),
					"the caller lost access to its own admin profile");
		}


		@Test
		@DisplayName("GET /auth/updated/getall returns only the caller's own profile")
		void profileListingIsScoped() {
			// This was a bare findAll() over updated_profile -- every tenant's
			// taxId, businessId, both emails and both phone numbers. The
			// handler did call getCurrentAdminId() and discard it.
			// primary_email is unique and other tests in this class create rows
			// with the fixture addresses, so these use their own.
			jdbc.update("DELETE FROM updated_profile WHERE id IN (?,?)", TENANT_A, TENANT_B);
			jdbc.update("INSERT INTO updated_profile (id, full_name, primary_email, company_name, tax_id) "
					+ "VALUES (?,?,?,?,?)",
					TENANT_A, "Alpha Admin", "profile-a-20260904@security-test.invalid",
					"Alpha Corp", "ALPHA-TAXID-20260904");
			jdbc.update("INSERT INTO updated_profile (id, full_name, primary_email, company_name, tax_id) "
					+ "VALUES (?,?,?,?,?)",
					TENANT_B, "Victim Admin", "profile-b-20260904@security-test.invalid",
					"Victim Corp", "VICTIM-TAXID-20260904");
			try {
				ResponseEntity<String> response = asTenantA(HttpMethod.GET, "/auth/updated/getall");
				assertEquals(HttpStatus.OK, response.getStatusCode());
				assertTrue(response.getBody().contains("ALPHA-TAXID-20260904"),
						"the caller's own profile is missing, so the assertion below proves nothing");
				assertFalse(response.getBody().contains("VICTIM-TAXID-20260904"),
						"another tenant's tax id was returned");
				assertFalse(response.getBody().contains("Victim Corp"),
						"another tenant's company name was returned");
			} finally {
				jdbc.update("DELETE FROM updated_profile WHERE id IN (?,?)", TENANT_A, TENANT_B);
			}
		}

		@Test
		@DisplayName("check-email is a platform-wide duplicate check, by design")
		void checkEmailIsPlatformWide() {
			// Not a tenant-scoped resource: registration has to know whether an
			// address is taken anywhere before creating an account, and the
			// endpoint is permitAll for the same reason /companies is. The
			// email-existence oracle is inherent to any registration flow.
			assertEquals(HttpStatus.OK,
					call(HttpMethod.GET, "/auth/check-email/" + B_EMAIL, null, null).getStatusCode(),
					"the pre-authentication duplicate check was broken");
		}
	}

	// ======================================================================
	@Nested
	@DisplayName("G-45  GET /auth/{filename}")
	class UploadedFiles {

		private static final String A_FILE = "aaaaaaaa-2026-0904-aaaa-attackerlogo.png";
		private static final String B_FILE = "bbbbbbbb-2026-0904-bbbb-victimlogo.png";
		private static final String ORPHAN = "cccccccc-2026-0904-cccc-orphan.png";

		@BeforeEach
		void stageFiles() {
			write(A_FILE, "attacker logo 20260904");
			write(B_FILE, "VICTIM-FILE-SECRET-20260904");
			write(ORPHAN, "orphan 20260904");
			// Files are referenced from manage_users.companylogo; the fixture
			// rows are the outer class's tenant A and tenant B records.
			jdbc.update("UPDATE manage_users SET companylogo = ? WHERE id = ?", A_FILE, A_ADMIN_MU);
			jdbc.update("UPDATE manage_users SET companylogo = ? WHERE id = ?", B_FILE, B_MU);
		}

		@AfterEach
		void removeFiles() {
			jdbc.update("UPDATE manage_users SET companylogo = NULL WHERE id IN (?,?)", A_ADMIN_MU, B_MU);
			for (String f : new String[] { A_FILE, B_FILE, ORPHAN }) {
				try {
					java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get("uploads", f));
				} catch (java.io.IOException ignored) {
					// a leftover fixture in a build directory is not worth failing for
				}
			}
		}

		private void write(String name, String content) {
			try {
				java.nio.file.Path dir = java.nio.file.Paths.get("uploads");
				java.nio.file.Files.createDirectories(dir);
				java.nio.file.Files.writeString(dir.resolve(name), content);
			} catch (java.io.IOException e) {
				throw new IllegalStateException("could not stage " + name, e);
			}
		}

		@Test
		@DisplayName("positive control: the caller's own file is served")
		void ownFileServed() {
			// Without this, every assertion below could pass because the
			// endpoint refuses everything.
			ResponseEntity<String> response = asTenantA(HttpMethod.GET, "/auth/" + A_FILE);
			assertEquals(HttpStatus.OK, response.getStatusCode(),
					"the caller's own upload was not served, so the negatives prove nothing");
			assertTrue(response.getBody().contains("attacker logo 20260904"));
		}

		@Test
		@DisplayName("another tenant's file is not served by name")
		void foreignFileRefused() {
			ResponseEntity<String> response = asTenantA(HttpMethod.GET, "/auth/" + B_FILE);
			assertNotEquals(HttpStatus.OK, response.getStatusCode(),
					"another tenant's upload was served");
			assertFalse(response.getBody() != null
					&& response.getBody().contains("VICTIM-FILE-SECRET-20260904"),
					"another tenant's file contents were returned");
		}

		@Test
		@DisplayName("a file on disk that nobody references is not served")
		void orphanRefused() {
			// The question is "does your tenant reference this file", not "is it
			// readable".
			assertNotEquals(HttpStatus.OK, asTenantA(HttpMethod.GET, "/auth/" + ORPHAN).getStatusCode(),
					"an unreferenced file was served");
		}

		@Test
		@DisplayName("path traversal cannot escape the upload directory")
		void traversalBlocked() {
			// loadFile resolved and normalised the name but never checked the
			// result was still inside uploads/, so "../../.." escaped it and any
			// authenticated user could read any file the process could read.
			for (String probe : new String[] {
					"../../../../etc/passwd",
					"..%2F..%2F..%2Fetc%2Fpasswd",
					"../pom.xml",
					"../../pom.xml",
			}) {
				ResponseEntity<String> response = asTenantA(HttpMethod.GET, "/auth/" + probe);
				assertNotEquals(HttpStatus.OK, response.getStatusCode(),
						"traversal succeeded for " + probe);
				assertFalse(response.getBody() != null
						&& (response.getBody().contains("root:") || response.getBody().contains("<artifactId>")),
						"file contents outside the upload directory were returned for " + probe);
			}
		}

		@Test
		@DisplayName("unauthenticated access is refused")
		void unauthenticatedRefused() {
			assertEquals(HttpStatus.UNAUTHORIZED,
					call(HttpMethod.GET, "/auth/" + A_FILE, null, null).getStatusCode());
		}

		@Test
		@DisplayName("the victim can still fetch its own file")
		void victimUnaffected() {
			assertEquals(HttpStatus.OK, asTenantB(HttpMethod.GET, "/auth/" + B_FILE).getStatusCode(),
					"the owner lost access to its own upload");
		}
	}

	// ======================================================================
	@Nested
	@DisplayName("G-42/43/44  the company registry")
	class CompanyRegistry {

		private static final String A_DOMAIN = "tenant-a.example.com";
		private static final String B_DOMAIN = "victim-company-20260904.example";

		@BeforeEach
		void seedRegistry() {
			jdbc.update("DELETE FROM company_registry WHERE company_domain IN (?,?)", A_DOMAIN, B_DOMAIN);
			// The column is is_active, not active, and registered_at is NOT NULL.
			jdbc.update("INSERT INTO company_registry (id, company_name, company_domain, schema_name, "
					+ "admin_email, is_active, registered_at) VALUES (?,?,?,?,?,true,now())",
					9950001L, "Tenant A", A_DOMAIN, "tenant_a_example_com", A_ADMIN_EMAIL);
			jdbc.update("INSERT INTO company_registry (id, company_name, company_domain, schema_name, "
					+ "admin_email, is_active, registered_at) VALUES (?,?,?,?,?,true,now())",
					9950900L, "VICTIM-COMPANY-20260904", B_DOMAIN, "victim_company_20260904",
					"victim-20260904@security-test.invalid");
		}

		@AfterEach
		void removeRegistryFixtures() {
			jdbc.update("DELETE FROM company_registry WHERE company_domain IN (?,?)", A_DOMAIN, B_DOMAIN);
		}

		private boolean isActive(String domain) {
			Boolean active = jdbc.queryForObject(
					"SELECT is_active FROM company_registry WHERE company_domain = ?", Boolean.class, domain);
			return Boolean.TRUE.equals(active);
		}

		@Test
		@DisplayName("a tenant admin cannot deactivate another company")
		void cannotDeactivateAnotherCompany() {
			// The gate was hasRole('ADMIN'), which every company administrator
			// holds. It was not exploitable, because JwtAuthFilter skipped the
			// whole "/companies" prefix and no token was ever parsed on this
			// path -- so it answered 403 to everyone, including its intended
			// users. Both halves are fixed: the path authenticates now, and the
			// gate is SUPERADMIN, so this refusal is an authorization decision
			// rather than an accident.
			ResponseEntity<String> response =
					asTenantA(HttpMethod.PUT, "/companies/" + B_DOMAIN + "/deactivate", null);
			assertTrue(response.getStatusCode().is4xxClientError(),
					"deactivating another company answered " + response.getStatusCode());
			assertTrue(isActive(B_DOMAIN), "another company was deactivated");
		}

		@Test
		@DisplayName("a tenant admin cannot reprovision another company's schema")
		void cannotReprovisionAnotherCompany() {
			// This executes DDL against the named tenant's schema.
			ResponseEntity<String> response =
					asTenantA(HttpMethod.POST, "/companies/" + B_DOMAIN + "/reprovision", null);
			assertTrue(response.getStatusCode().is4xxClientError(),
					"reprovisioning another company answered " + response.getStatusCode());
			assertFalse(response.getBody() != null && response.getBody().contains("victim_company_20260904"),
					"the response disclosed another company's schema name");
		}

		@Test
		@DisplayName("a tenant admin cannot reprovision every schema on the platform")
		void cannotReprovisionAll() {
			assertTrue(asTenantA(HttpMethod.POST, "/companies/reprovision-all", null)
					.getStatusCode().is4xxClientError(),
					"a tenant admin could trigger DDL across every tenant schema");
		}

		@Test
		@DisplayName("the read endpoints stay public — the login screen needs them before a session")
		void readEndpointsRemainPublic() {
			// Deliberate, and load-bearing: Angular's auth.interceptor and the
			// gateway both list /companies as a no-token path, so a login or
			// registration screen can resolve a tenant before anyone signs in.
			// Locking these down breaks that flow, which is why the sensitive
			// fields are kept out at the entity instead.
			assertEquals(HttpStatus.OK, call(HttpMethod.GET, "/companies", null, null).getStatusCode(),
					"the pre-authentication company lookup was broken");
			assertEquals(HttpStatus.OK,
					call(HttpMethod.GET, "/companies/" + A_DOMAIN, null, null).getStatusCode());
		}

		@Test
		@DisplayName("but they never disclose a schema name or an administrator's address")
		void publicReadsExcludeInfrastructureAndPii() {
			// These are @JsonIgnore'd on the entity. That is the actual control
			// on this path, so it is the thing to assert.
			String all = call(HttpMethod.GET, "/companies", null, null).getBody();
			String one = call(HttpMethod.GET, "/companies/" + B_DOMAIN, null, null).getBody();
			for (String body : new String[] { all, one }) {
				assertFalse(body.contains("victim_company_20260904"),
						"a tenant's Postgres schema name was disclosed without authentication");
				assertFalse(body.contains("victim-20260904@security-test.invalid"),
						"a tenant administrator's address was disclosed without authentication");
			}
			assertTrue(all.contains("VICTIM-COMPANY-20260904"),
					"the listing returned nothing, so the assertions above prove nothing");
		}

		@Test
		@DisplayName("a tenant admin CAN still reach its own company's reprovision")
		void ownReprovisionReachable() {
			// The positive control that matters after making the path
			// authenticate: the endpoint must be usable by its intended caller,
			// not merely closed. A 5xx from the provisioning service itself is
			// acceptable here -- what must not happen is a 403.
			ResponseEntity<String> response =
					asTenantA(HttpMethod.POST, "/companies/" + A_DOMAIN + "/reprovision", null);
			assertNotEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
					"a tenant admin cannot reprovision its own schema, so the endpoint is still dead");
		}

		@Test
		@DisplayName("unauthenticated mutation is refused")
		void unauthenticatedMutationRefused() {
			// 4xx rather than a specific code: this service answers anonymous
			// requests on a @PreAuthorize'd handler with 403, not 401, and the
			// property that matters is that it refuses and changes nothing.
			assertTrue(call(HttpMethod.PUT, "/companies/" + B_DOMAIN + "/deactivate", null, null)
					.getStatusCode().is4xxClientError());
			assertTrue(call(HttpMethod.POST, "/companies/reprovision-all", null, null)
					.getStatusCode().is4xxClientError());
			assertTrue(isActive(B_DOMAIN));
		}
	}

	// ======================================================================
	@Nested
	@DisplayName("V-0  the tenant key survives sub-user creation")
	class TenantKeyPropagation {

		private static final String NEW_EMAIL = "subuser-20260904@tenant-a.example.com";

		@AfterEach
		void removeOnlyWhatThisTestCreated() {
			jdbc.update("DELETE FROM manage_users WHERE email = ?", NEW_EMAIL);
			jdbc.update("DELETE FROM user_info WHERE email = ?", NEW_EMAIL);
		}

		@Test
		@DisplayName("createUser writes company_domain to user_info, not only manage_users")
		void companyDomainReachesUserInfo() {
			// The root cause of the customer-service tenant collapse (V-0).
			// createUser resolved the domain and wrote it to manage_users, then
			// built the matching user_info row by copying 22 fields from that
			// record -- and not this one. A null user_info.company_domain means
			// JwtServiceImpl omits the companyDomain claim, TenantFilter selects
			// no schema, and TenantRoutingDataSource falls back to the shared
			// default pool where unrelated tenants see each other's rows.
			ResponseEntity<String> response = asTenantA(HttpMethod.POST, "/auth/manageusers/save",
					"{\"email\":\"" + NEW_EMAIL + "\",\"firstName\":\"Sub\","
							+ "\"lastName\":\"User\",\"fullName\":\"Sub User 20260904\","
							+ "\"role\":{\"roleId\":" + A_ROLE + "}}");
			assertEquals(HttpStatus.OK, response.getStatusCode());

			String inManageUsers = scalar("SELECT company_domain FROM manage_users WHERE email = ?", NEW_EMAIL);
			String inUserInfo = scalar("SELECT company_domain FROM user_info WHERE email = ?", NEW_EMAIL);

			assertNotNull(inManageUsers, "the domain was not resolved at all");
			assertEquals(inManageUsers, inUserInfo,
					"user_info.company_domain does not match manage_users.company_domain, so this "
							+ "user's token will carry no tenant and route to the shared schema");
		}

		@Test
		@DisplayName("the domain is the caller's own, taken from the session")
		void domainComesFromTheCaller() {
			asTenantA(HttpMethod.POST, "/auth/manageusers/save",
					"{\"email\":\"" + NEW_EMAIL + "\",\"firstName\":\"Sub\","
							+ "\"lastName\":\"User\",\"fullName\":\"Sub User 20260904\","
							+ "\"companyDomain\":\"attacker-controlled.example\","
							+ "\"role\":{\"roleId\":" + A_ROLE + "}}");
			// A body-supplied domain must not decide which schema the new user
			// reads from -- that would be tenant assignment by request body.
			assertEquals("tenant-a.example.com",
					scalar("SELECT company_domain FROM user_info WHERE email = ?", NEW_EMAIL),
					"the request body chose the new user's tenant");
		}
	}

	// ======================================================================
	@Nested
	@DisplayName("T-14  privilege creation")
	class PrivilegeCreation {

		@AfterEach
		void removeWhatTheTestCreated() {
			jdbc.update("DELETE FROM role_privileges WHERE privilegeid IN "
					+ "(SELECT privilegeid FROM privileges WHERE name LIKE 'IT_NEW_%')");
			jdbc.update("DELETE FROM privileges WHERE name LIKE 'IT_NEW_%'");
		}

		@Test
		@DisplayName("a created privilege is owned by the caller's tenant, not left unowned")
		void createdPrivilegeIsOwned() {
			assertEquals(HttpStatus.OK,
					asTenantA(HttpMethod.POST, "/auth/privileges/save",
							"{\"name\":\"IT_NEW_OWNED\",\"category\":\"IT_NEW_CAT\",\"cardType\":\"CARD\"}")
							.getStatusCode());
			assertEquals(TENANT_A,
					count("SELECT admin_id FROM privileges WHERE name = ?", "IT_NEW_OWNED"),
					"a null admin_id puts the privilege in the shared catalogue: visible to "
							+ "every tenant, and deletable by none");
		}

		@Test
		@DisplayName("creating a privilege does NOT grant it to another tenant's Admin role")
		void createDoesNotFanOutAcrossTenants() {
			// The original implementation assigned every new privilege to
			// "every Admin role across all tenants", so this one call rewrote
			// other tenants' access control.
			long before = count("SELECT count(*) FROM role_privileges WHERE roleid = ?", B_ROLE);
			asTenantA(HttpMethod.POST, "/auth/privileges/save",
					"{\"name\":\"IT_NEW_FANOUT\",\"category\":\"IT_NEW_CAT\",\"cardType\":\"CARD\"}");
			assertEquals(before, count("SELECT count(*) FROM role_privileges WHERE roleid = ?", B_ROLE),
					"another tenant's role gained a privilege it never asked for");
		}

		@Test
		@DisplayName("a created privilege is not visible to another tenant")
		void createdPrivilegeIsNotVisibleToOtherTenants() {
			asTenantA(HttpMethod.POST, "/auth/privileges/save",
					"{\"name\":\"IT_NEW_HIDDEN\",\"category\":\"IT_NEW_CAT\",\"cardType\":\"CARD\"}");
			assertFalse(asTenantB(HttpMethod.GET, "/auth/privileges/getall").getBody()
					.contains("IT_NEW_HIDDEN"),
					"a newly created privilege leaked into another tenant's catalogue");
		}

		@Test
		@DisplayName("positive control: the creator can see and delete what it created")
		void creatorCanSeeAndDelete() {
			asTenantA(HttpMethod.POST, "/auth/privileges/save",
					"{\"name\":\"IT_NEW_MINE\",\"category\":\"IT_NEW_CAT\",\"cardType\":\"CARD\"}");
			assertTrue(asTenantA(HttpMethod.GET, "/auth/privileges/getall").getBody()
					.contains("IT_NEW_MINE"), "the creator cannot see its own privilege");

			long id = count("SELECT privilegeid FROM privileges WHERE name = ?", "IT_NEW_MINE");
			assertEquals(HttpStatus.OK,
					asTenantA(HttpMethod.DELETE, "/auth/privileges/" + id).getStatusCode(),
					"an unowned privilege cannot be deleted by anyone, including its creator");
			assertEquals(0, count("SELECT count(*) FROM privileges WHERE name = ?", "IT_NEW_MINE"));
		}

		@Test
		@DisplayName("positive control: the creator's own Admin role is granted it")
		void creatorsAdminRoleIsGranted() {
			asTenantA(HttpMethod.POST, "/auth/privileges/save",
					"{\"name\":\"IT_NEW_GRANTED\",\"category\":\"IT_NEW_CAT\",\"cardType\":\"CARD\"}");
			assertEquals(1,
					count("SELECT count(*) FROM role_privileges rp JOIN privileges p "
							+ "ON p.privilegeid = rp.privilegeid WHERE rp.roleid = ? AND p.name = ?",
							A_ROLE, "IT_NEW_GRANTED"),
					"the creating tenant's own Admin role did not receive the privilege");
		}

		@Test
		@DisplayName("a body-supplied adminId is ignored")
		void bodyAdminIdIgnored() {
			asTenantA(HttpMethod.POST, "/auth/privileges/save",
					"{\"name\":\"IT_NEW_INJECT\",\"category\":\"IT_NEW_CAT\",\"cardType\":\"CARD\","
							+ "\"adminId\":" + TENANT_B + "}");
			assertEquals(TENANT_A,
					count("SELECT admin_id FROM privileges WHERE name = ?", "IT_NEW_INJECT"),
					"the request body chose the owning tenant");
		}
	}

	// ======================================================================
	@Nested
	@DisplayName("T-12/T-13  role-privilege assignment")
	class RolePrivileges {

		// Unlike its neighbours, this group mutates grants, and the outer
		// fixture is seeded once per class. Each test therefore restores the
		// seeded grant set first so results cannot depend on run order.
		@BeforeEach
		void restoreSeededGrants() {
			jdbc.update("DELETE FROM role_privileges WHERE roleid = ?", A_ROLE);
			jdbc.update("INSERT INTO role_privileges (roleid, privilegeid) VALUES (?,?)", A_ROLE, A_PRIVILEGE);
		}

		// Four separate routes reach the same two operations. Each one was
		// unscoped, so each needs its own test: fixing three of four is the
		// exact mistake this whole audit keeps finding.

		@Test
		@DisplayName("cross-tenant read is refused on /auth/roles/{id}/privileges")
		void crossTenantReadViaRolesRouteRefused() {
			assertDeniedWithoutLeaking(asTenantA(HttpMethod.GET, "/auth/roles/" + B_ROLE + "/privileges"),
					"GET /auth/roles/{other tenant}/privileges");
		}

		@Test
		@DisplayName("cross-tenant read is refused on /auth/privileges/role/{id}")
		void crossTenantReadViaPrivilegesRouteRefused() {
			assertDeniedWithoutLeaking(asTenantA(HttpMethod.GET, "/auth/privileges/role/" + B_ROLE),
					"GET /auth/privileges/role/{other tenant}");
		}

		@Test
		@DisplayName("the grouped catalogue does not name another tenant's privileges")
		void groupedCatalogueIsTenantScoped() {
			ResponseEntity<String> response =
					asTenantA(HttpMethod.GET, "/auth/roles/" + A_ROLE + "/privileges");
			assertEquals(HttpStatus.OK, response.getStatusCode());
			assertFalse(response.getBody().contains("TENANT_B_PRIV"),
					"another tenant's privilege name was disclosed through the role view");
			assertFalse(response.getBody().contains("TENANT_B_CAT"),
					"another tenant's category was disclosed through the role view");
		}

		@Test
		@DisplayName("the shared null-owner catalogue stays visible")
		void sharedCatalogueStaysVisible() {
			assertTrue(asTenantA(HttpMethod.GET, "/auth/roles/" + A_ROLE + "/privileges").getBody()
					.contains("SHARED_PLATFORM_PRIV"),
					"the unowned platform catalogue must not be scoped away");
		}

		@Test
		@DisplayName("cross-tenant assign via POST is refused AND grants nothing")
		void crossTenantAssignViaPostRefused() {
			assertDeniedWithoutLeaking(
					asTenantA(HttpMethod.POST, "/auth/roles/privilege/save",
							"{\"roleId\":" + B_ROLE + ",\"category\":\"TENANT_B_CAT\",\"privilegeIds\":["
									+ B_PRIVILEGE + "]}"),
					"POST /auth/roles/privilege/save {other tenant's role}");
			assertEquals(0, count("SELECT count(*) FROM role_privileges WHERE roleid = ?", B_ROLE),
					"another tenant's role was granted a privilege");
		}

		@Test
		@DisplayName("cross-tenant assign via PUT is refused AND grants nothing")
		void crossTenantAssignViaPutRefused() {
			assertDeniedWithoutLeaking(
					asTenantA(HttpMethod.PUT, "/auth/roles/" + B_ROLE + "/privileges",
							"{\"category\":\"TENANT_B_CAT\",\"privilegeIds\":[" + B_PRIVILEGE + "]}"),
					"PUT /auth/roles/{other tenant}/privileges");
			assertEquals(0, count("SELECT count(*) FROM role_privileges WHERE roleid = ?", B_ROLE),
					"another tenant's role was granted a privilege");
		}

		@Test
		@DisplayName("a foreign privilege id cannot be smuggled onto the caller's OWN role")
		void foreignPrivilegeIdRejectedOnOwnRole() {
			// The caller owns the role, so the tenant check passes. The
			// privilege ids in the body are the attack surface here.
			ResponseEntity<String> response = asTenantA(HttpMethod.POST, "/auth/roles/privilege/save",
					"{\"roleId\":" + A_ROLE + ",\"category\":\"SHARED_CAT\",\"privilegeIds\":["
							+ B_PRIVILEGE + "]}");
			assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
			assertFalse(response.getBody().contains("TENANT_B_PRIV"),
					"the rejection named the foreign privilege");
			assertEquals(0,
					count("SELECT count(*) FROM role_privileges WHERE roleid = ? AND privilegeid = ?",
							A_ROLE, B_PRIVILEGE),
					"a foreign privilege was attached to the caller's own role");
		}

		@Test
		@DisplayName("an own privilege outside the stated category is rejected")
		void outOfCategoryPrivilegeRejected() {
			// Otherwise "category" is decoration: the caller names one
			// category and edits grants in another.
			assertEquals(HttpStatus.BAD_REQUEST,
					asTenantA(HttpMethod.POST, "/auth/roles/privilege/save",
							"{\"roleId\":" + A_ROLE + ",\"category\":\"SHARED_CAT\",\"privilegeIds\":["
									+ A_PRIVILEGE + "]}").getStatusCode());
		}

		@Test
		@DisplayName("positive control: assigning an own privilege in its own category works")
		void ownAssignmentWorks() {
			jdbc.update("DELETE FROM role_privileges WHERE roleid = ? AND privilegeid = ?", A_ROLE, A_PRIVILEGE);
			assertEquals(HttpStatus.OK,
					asTenantA(HttpMethod.POST, "/auth/roles/privilege/save",
							"{\"roleId\":" + A_ROLE + ",\"category\":\"TENANT_A_CAT\",\"privilegeIds\":["
									+ A_PRIVILEGE + "]}").getStatusCode());
			assertEquals(1,
					count("SELECT count(*) FROM role_privileges WHERE roleid = ? AND privilegeid = ?",
							A_ROLE, A_PRIVILEGE),
					"the caller's own grant was lost");
		}

		@Test
		@DisplayName("positive control: unchecking an own privilege still revokes it")
		void ownRevocationWorks() {
			assertEquals(HttpStatus.OK,
					asTenantA(HttpMethod.POST, "/auth/roles/privilege/save",
							"{\"roleId\":" + A_ROLE + ",\"category\":\"TENANT_A_CAT\",\"privilegeIds\":[]}")
							.getStatusCode());
			assertEquals(0,
					count("SELECT count(*) FROM role_privileges WHERE roleid = ? AND privilegeid = ?",
							A_ROLE, A_PRIVILEGE),
					"revocation stopped working, so the category filter is too strict");
		}

		@Test
		@DisplayName("positive control: the shared null-owner privilege is assignable")
		void sharedPrivilegeAssignable() {
			assertEquals(HttpStatus.OK,
					asTenantA(HttpMethod.POST, "/auth/roles/privilege/save",
							"{\"roleId\":" + A_ROLE + ",\"category\":\"SHARED_CAT\",\"privilegeIds\":[7000]}")
							.getStatusCode());
			assertEquals(1,
					count("SELECT count(*) FROM role_privileges WHERE roleid = ? AND privilegeid = ?",
							A_ROLE, 7000L),
					"the shared catalogue became unassignable");
		}

		@Test
		@DisplayName("unauthenticated access is refused on all four routes")
		void unauthenticatedRefused() {
			assertEquals(HttpStatus.UNAUTHORIZED,
					call(HttpMethod.GET, "/auth/roles/" + A_ROLE + "/privileges", null, null).getStatusCode());
			assertEquals(HttpStatus.UNAUTHORIZED,
					call(HttpMethod.GET, "/auth/privileges/role/" + A_ROLE, null, null).getStatusCode());
			assertEquals(HttpStatus.UNAUTHORIZED,
					call(HttpMethod.POST, "/auth/roles/privilege/save", null,
							"{\"roleId\":1001,\"category\":\"TENANT_A_CAT\",\"privilegeIds\":[]}")
							.getStatusCode());
			assertEquals(HttpStatus.UNAUTHORIZED,
					call(HttpMethod.PUT, "/auth/roles/" + A_ROLE + "/privileges", null,
							"{\"category\":\"TENANT_A_CAT\",\"privilegeIds\":[]}").getStatusCode());
		}
	}

	// ======================================================================
	@Nested
	@DisplayName("T-6  privileges by id")
	class Privileges {

		@Test
		@DisplayName("cross-tenant read is refused")
		void crossTenantReadRefused() {
			assertDeniedWithoutLeaking(asTenantA(HttpMethod.GET, "/auth/privileges/" + B_PRIVILEGE),
					"GET /auth/privileges/{other tenant}");
		}

		@Test
		@DisplayName("cross-tenant update is refused AND the privilege keeps its name")
		void crossTenantUpdateRefused() {
			assertDeniedWithoutLeaking(
					asTenantA(HttpMethod.PUT, "/auth/privileges/" + B_PRIVILEGE,
							"{\"name\":\"HIJACKED_PRIV\",\"category\":\"TENANT_B_CAT\"}"),
					"PUT /auth/privileges/{other tenant}");
			assertEquals("TENANT_B_PRIV",
					scalar("SELECT name FROM privileges WHERE privilegeid = ?", B_PRIVILEGE));
		}

		@Test
		@DisplayName("cross-tenant delete is refused AND the whole category survives")
		void crossTenantCategoryDeleteRefused() {
			// DELETE /auth/privileges/{id} maps to deletePrivilegesByCategoryId,
			// so one request used to remove another tenant's entire category
			// along with its role_privileges rows.
			assertDeniedWithoutLeaking(
					asTenantA(HttpMethod.DELETE, "/auth/privileges/" + B_PRIVILEGE),
					"DELETE /auth/privileges/{other tenant}");
			assertEquals(1, count("SELECT count(*) FROM privileges WHERE admin_id = ?", TENANT_B),
					"another tenant's privilege category was deleted");
		}

		@Test
		@DisplayName("own-tenant privilege is readable")
		void ownTenantPrivilegeReadable() {
			assertEquals(HttpStatus.OK,
					asTenantA(HttpMethod.GET, "/auth/privileges/" + A_PRIVILEGE).getStatusCode());
		}
	}

	// ======================================================================
	@Nested
	@DisplayName("T-3  profile by email")
	class ProfileByEmail {

		@Test
		@DisplayName("another user's profile is refused and no marker leaks")
		void crossTenantProfileRefused() {
			// This endpoint had no authorization at all: it returned bankDetails,
			// taxId, ein and address for any address supplied in the path.
			assertDeniedWithoutLeaking(
					asTenantA(HttpMethod.GET, "/auth/updated/email/" + B_EMAIL),
					"GET /auth/updated/email/{other tenant}");
		}

		@Test
		@DisplayName("own profile still returns own bank details")
		void ownProfileStillWorks() {
			ResponseEntity<String> response =
					asTenantA(HttpMethod.GET, "/auth/updated/email/" + A_ADMIN_EMAIL);
			assertEquals(HttpStatus.OK, response.getStatusCode());
			assertTrue(String.valueOf(response.getBody()).contains(A_OWN_MARKER),
					"the caller's own data was removed — the fix broke functionality");
		}

		@Test
		@DisplayName("same-tenant colleague's profile is readable")
		void sameTenantColleagueReadable() {
			assertEquals(HttpStatus.OK,
					asTenantA(HttpMethod.GET, "/auth/updated/email/" + A_COLLEAGUE_EMAIL).getStatusCode());
		}
	}

	// ======================================================================
	@Nested
	@DisplayName("S-2  entity serialization on /auth/me")
	class SelfProfile {

		@Test
		@DisplayName("returns own bank details and privileges, but never a creator record")
		void doesNotExposeCreator() {
			ResponseEntity<String> response = asTenantA(HttpMethod.GET, "/auth/me");
			assertEquals(HttpStatus.OK, response.getStatusCode());
			String body = String.valueOf(response.getBody());
			assertAll(
					() -> assertTrue(body.contains(A_OWN_MARKER), "own bank details are missing"),
					() -> assertFalse(body.contains("\"createdBy\""),
							"createdBy is serialised again — it disclosed the creator's bank account"),
					() -> assertFalse(body.contains(B_ACCOUNT)));
		}
	}

	// ======================================================================
	@Nested
	@DisplayName("T-7/T-8/T-9  listing scope")
	class ListingScope {

		@Test
		@DisplayName("role search does not return another tenant's roles")
		void roleSearchIsScoped() {
			// GET /auth/roles/search and /auth/roles/adminId/search were two
			// endpoints with two implementations; only one scoped by tenant. The
			// unscoped one returned every tenant's roles with their privilege
			// names. They now share the scoped implementation.
			ResponseEntity<String> response = asTenantA(HttpMethod.GET, "/auth/roles/search");
			assertEquals(HttpStatus.OK, response.getStatusCode());
			String body = String.valueOf(response.getBody());
			assertFalse(body.contains("TENANT_B_ROLE"),
					"role search exposed another tenant's role");
			assertTrue(body.contains("ADMIN"), "the caller's own role vanished from search");
		}

		@Test
		@DisplayName("the aliased search path is scoped the same way")
		void aliasedRoleSearchIsScoped() {
			ResponseEntity<String> response = asTenantA(HttpMethod.GET, "/auth/roles/adminId/search");
			assertEquals(HttpStatus.OK, response.getStatusCode());
			assertFalse(String.valueOf(response.getBody()).contains("TENANT_B_ROLE"));
		}

		@Test
		@DisplayName("privilege listing hides other tenants but keeps the shared catalogue")
		void privilegeListingIsScopedButKeepsSharedOnes() {
			// Scoping strictly to the caller's tenant would empty the privileges
			// screen, because the shared catalogue has a null owner. Both halves
			// are asserted so neither can be traded for the other.
			ResponseEntity<String> response = asTenantA(HttpMethod.GET, "/auth/privileges/getall");
			assertEquals(HttpStatus.OK, response.getStatusCode());
			String body = String.valueOf(response.getBody());
			assertAll(
					() -> assertFalse(body.contains("TENANT_B_PRIV"),
							"privilege listing exposed another tenant's privilege"),
					() -> assertTrue(body.contains("SHARED_PLATFORM_PRIV"),
							"the shared platform catalogue disappeared — scoping went too far"),
					() -> assertTrue(body.contains("TENANT_A_PRIV"),
							"the caller's own privilege disappeared"));
		}

		@Test
		@DisplayName("manage-users listing does not return another tenant's users")
		void manageUsersListingIsScoped() {
			ResponseEntity<String> response = asTenantA(HttpMethod.GET, "/auth/manageusers/getall");
			assertEquals(HttpStatus.OK, response.getStatusCode());
			assertFalse(String.valueOf(response.getBody()).contains(B_NAME),
					"the user listing exposed another tenant's user");
		}
	}

	// ======================================================================
	@Nested
	@DisplayName("T-10  account-number OTP targets the caller only")
	class AccountNumberOtp {

		@Test
		@DisplayName("an OTP cannot be sent to somebody else's address")
		void cannotTargetAnotherAddress() {
			// The address used to come from the request body with no check that
			// it was the caller's. That let any authenticated user have the
			// service deliver a genuine, branded security email to a chosen
			// victim — and spend that victim's resend allowance.
			long before = count("SELECT count(*) FROM otp_challenges WHERE purpose = ?",
					"ACCOUNT_NUMBER_CHANGE");

			ResponseEntity<String> response = asTenantA(HttpMethod.POST,
					"/auth/accountnumbersend-otp", "{\"email\":\"" + B_EMAIL + "\"}");

			assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
					"sending an OTP to another address must be refused");
			assertEquals(before,
					count("SELECT count(*) FROM otp_challenges WHERE purpose = ?",
							"ACCOUNT_NUMBER_CHANGE"),
					"a challenge was created against the victim's account anyway");
		}

		@Test
		@DisplayName("the caller's own address is accepted")
		void ownAddressAccepted() {
			ResponseEntity<String> response = asTenantA(HttpMethod.POST,
					"/auth/accountnumbersend-otp", "{\"email\":\"" + A_ADMIN_EMAIL + "\"}");
			assertTrue(response.getStatusCode().is2xxSuccessful(),
					"the caller must still be able to re-verify themselves, got "
							+ response.getStatusCode());
		}
	}

	// ======================================================================
	@Nested
	@DisplayName("T-11  mass assignment on create")
	class MassAssignment {

		@Test
		@DisplayName("a role's tenant comes from the token, not the request body")
		void createRoleIgnoresBodyTenant() {
			// convertToEntity copied dto.getAdminId() straight through, so a body
			// carrying {"adminId": 900} created a role owned by tenant 900 —
			// injected into another tenant's namespace by a tenant-1001 caller.
			ResponseEntity<String> response = asTenantA(HttpMethod.POST, "/auth/roles/save",
					"{\"roleName\":\"MA_CLAIMS_OTHER_TENANT\",\"description\":\"x\","
							+ "\"status\":\"ACTIVE\",\"adminId\":" + TENANT_B + "}");

			assertTrue(response.getStatusCode().is2xxSuccessful(),
					"role creation should succeed, got " + response.getStatusCode());

			Long owner = jdbc.queryForObject(
					"SELECT admin_id FROM roles WHERE role_name = ?", Long.class,
					"MA_CLAIMS_OTHER_TENANT");
			assertEquals(TENANT_A, owner,
					"the role was created owned by tenant " + owner
							+ " — the request body chose the tenant");
		}

		@Test
		@DisplayName("a role created with no tenant in the body is owned, not orphaned")
		void createRoleSetsOwnerWhenBodyOmitsIt() {
			// The other half of the same bug: with no adminId in the body the
			// role saved with admin_id = NULL, which the tenant guards treat as
			// unreachable — so a role was invisible to whoever had just made it.
			ResponseEntity<String> response = asTenantA(HttpMethod.POST, "/auth/roles/save",
					"{\"roleName\":\"MA_NO_TENANT_IN_BODY\",\"description\":\"x\","
							+ "\"status\":\"ACTIVE\"}");
			assertTrue(response.getStatusCode().is2xxSuccessful());

			Long owner = jdbc.queryForObject(
					"SELECT admin_id FROM roles WHERE role_name = ?", Long.class,
					"MA_NO_TENANT_IN_BODY");
			assertEquals(TENANT_A, owner, "the role was orphaned with a null owner");

			// And it must actually be visible to its creator.
			assertTrue(String.valueOf(asTenantA(HttpMethod.GET, "/auth/roles/getall").getBody())
					.contains("MA_NO_TENANT_IN_BODY"),
					"the creator cannot see the role they just created");
		}

		@Test
		@DisplayName("settings writes land on the caller's own row, whatever id the body claims")
		void settingsWriteIsScopedToTheCaller() {
			// This endpoint is the one that was already right: the row is chosen
			// by the server-derived adminId and the body is copied through a
			// field allowlist. Pinned so it stays that way.
			jdbc.update("INSERT INTO updated_profile (id, primary_email, admin_id, timezone) "
					+ "VALUES (?,?,?,?)", 9000L, B_EMAIL, TENANT_B, "TENANT-B-TZ-SECRET");

			asTenantA(HttpMethod.PUT, "/auth/settings",
					"{\"id\":9000,\"adminId\":" + TENANT_B + ",\"timezone\":\"HIJACKED\","
							+ "\"primaryEmail\":\"" + B_EMAIL + "\"}");

			assertEquals("TENANT-B-TZ-SECRET",
					scalar("SELECT timezone FROM updated_profile WHERE id = ?", 9000L),
					"another tenant's settings row was modified");
		}
	}

	// ======================================================================
	@Nested
	@DisplayName("authentication boundary")
	class Authentication {

		@Test
		@DisplayName("no token is rejected on every protected path")
		void unauthenticatedRejected() {
			for (String path : new String[] { "/auth/me", "/auth/manageusers/" + B_MU,
					"/auth/updated/email/" + B_EMAIL, "/auth/roles/" + B_ROLE }) {
				ResponseEntity<String> response = call(HttpMethod.GET, path, null, null);
				assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(),
						path + " must require authentication");
			}
		}

		@Test
		@DisplayName("a malformed token is rejected, not treated as anonymous")
		void malformedTokenRejected() {
			ResponseEntity<String> response = call(HttpMethod.GET, "/auth/me", "not.a.jwt", null);
			assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
		}
	}
}
