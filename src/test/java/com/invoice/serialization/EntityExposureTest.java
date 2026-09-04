package com.invoice.serialization;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoice.entity.BankDetails;
import com.invoice.entity.CompanyRegistry;
import com.invoice.entity.ManageUsers;
import com.invoice.entity.Privilege;
import com.invoice.entity.Role;
import com.invoice.entity.User;

/**
 * What the API actually hands out when an entity is serialised.
 *
 * <p>These assert on the JSON, not on annotations, because the annotation is
 * not the contract — the rendered response is. Every marker below is a value
 * that must never appear in a response, and each one corresponds to a real
 * disclosure that was live in this service.
 *
 * <p>Deliberately not a Spring test. Jackson's behaviour here is a property of
 * the entity classes, so this runs in milliseconds and fails on a bad
 * annotation change without needing a context, a database or an HTTP call.
 */
class EntityExposureTest {

	private static final String CREATOR_ACCOUNT = "CREATOR-SECRET-ACCT-999";
	private static final String CREATOR_ROUTING = "CREATOR-ROUTING-999888777";
	private static final String OWN_ACCOUNT = "USER-OWN-ACCT-111";

	/**
	 * Configured as Spring Boot configures it, so these assertions reflect what
	 * the application really emits — including java.time handling.
	 */
	private final ObjectMapper mapper = new ObjectMapper()
			.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

	private String json(Object value) throws Exception {
		return mapper.writeValueAsString(value);
	}

	/** A user whose creator holds marker bank details. */
	private User userWithCreator() {
		BankDetails creatorBank = new BankDetails();
		creatorBank.setBankName("Creator Bank");
		creatorBank.setBankAccountNumber(CREATOR_ACCOUNT);
		creatorBank.setRoutingNumber(CREATOR_ROUTING);

		User creator = new User();
		creator.setId(900L);
		creator.setEmail("creator@example.com");
		creator.setBankDetails(List.of(creatorBank));

		BankDetails ownBank = new BankDetails();
		ownBank.setBankName("User Bank");
		ownBank.setBankAccountNumber(OWN_ACCOUNT);
		ownBank.setRoutingNumber("111222333");

		User user = new User();
		user.setId(1001L);
		user.setEmail("user@example.com");
		user.setBankDetails(List.of(ownBank));
		user.setCreatedBy(creator);
		return user;
	}

	// ---- /auth/me -----------------------------------------------------------

	@Test
	@DisplayName("a serialised User never carries its creator's bank account number")
	void creatorAccountNumberIsNeverSerialised() throws Exception {
		String body = json(userWithCreator());
		assertFalse(body.contains(CREATOR_ACCOUNT),
				"GET /auth/me returned the creator's bank account number. This was live: "
						+ "User.createdBy is a @ManyToOne to User, and Jackson walked into it.");
	}

	@Test
	@DisplayName("a serialised User never carries its creator's routing number")
	void creatorRoutingNumberIsNeverSerialised() throws Exception {
		assertFalse(json(userWithCreator()).contains(CREATOR_ROUTING));
	}

	@Test
	@DisplayName("createdBy is absent entirely, not merely emptied")
	void createdByIsNotSerialisedAtAll() throws Exception {
		String body = json(userWithCreator());
		assertFalse(body.contains("\"createdBy\""),
				"createdBy must not appear: no fetch graph can terminate a "
						+ "self-referential association, so it cannot be exposed safely");
		assertFalse(body.contains("creator@example.com"),
				"the creator's identity leaked even without the bank details");
	}

	@Test
	@DisplayName("the user's OWN bank details are still returned")
	void ownBankDetailsSurvive() throws Exception {
		// The fix must not hide legitimate data. The Angular profile dialog
		// reads profile.bankDetails.
		String body = json(userWithCreator());
		assertTrue(body.contains(OWN_ACCOUNT),
				"the user's own bank details were removed — the profile screen needs them");
		assertTrue(body.contains("\"bankDetails\""));
	}

	@Test
	@DisplayName("a null creator serialises without error")
	void nullCreatorIsFine() throws Exception {
		User user = new User();
		user.setId(1L);
		user.setEmail("solo@example.com");
		assertDoesNotThrow(() -> json(user));
	}

	// ---- ManageUsers --------------------------------------------------------

	@Test
	@DisplayName("a serialised ManageUsers exposes neither addedBy nor createdBy")
	void manageUsersDoesNotExposeItsUserReferences() throws Exception {
		// Both are EAGER @ManyToOne to User. createdBy was already ignored;
		// addedBy was not, and they are the same association to the same entity.
		BankDetails bank = new BankDetails();
		bank.setBankAccountNumber(CREATOR_ACCOUNT);
		bank.setRoutingNumber(CREATOR_ROUTING);
		User adder = new User();
		adder.setId(900L);
		adder.setEmail("adder@example.com");
		adder.setBankDetails(List.of(bank));

		ManageUsers mu = new ManageUsers();
		mu.setId(2001L);
		mu.setEmail("user@example.com");
		mu.setAddedBy(adder);
		mu.setCreatedBy(adder);

		String body = json(mu);
		assertAll(
				() -> assertFalse(body.contains("\"addedBy\""), "addedBy must not be serialised"),
				() -> assertFalse(body.contains("\"createdBy\""), "createdBy must not be serialised"),
				() -> assertFalse(body.contains(CREATOR_ACCOUNT),
						"an adder's bank account reached the response"),
				() -> assertFalse(body.contains(CREATOR_ROUTING)));
	}

	// ---- CompanyRegistry ----------------------------------------------------

	@Test
	@DisplayName("CompanyRegistry hides the tenant schema name and admin email")
	void companyRegistryHidesInfrastructureAndPii() throws Exception {
		// GET /companies is permitAll and returns this entity directly, so
		// anything serialised here is public to the internet.
		CompanyRegistry company = new CompanyRegistry();
		company.setId(1L);
		company.setCompanyName("Acme Ltd");
		company.setCompanyDomain("acme.example.com");
		company.setSchemaName("invoice_acme_example_com");
		company.setAdminEmail("tenant-admin@acme.example.com");

		String body = json(company);
		assertAll(
				() -> assertFalse(body.contains("invoice_acme_example_com"),
						"the tenant's Postgres schema name was public"),
				() -> assertFalse(body.contains("tenant-admin@acme.example.com"),
						"the tenant administrator's email was public"),
				() -> assertFalse(body.contains("\"schemaName\"")),
				() -> assertFalse(body.contains("\"adminEmail\"")),
				// What the endpoint legitimately offers must survive.
				() -> assertTrue(body.contains("Acme Ltd")),
				() -> assertTrue(body.contains("acme.example.com")));
	}

	// ---- recursion ----------------------------------------------------------

	@Test
	@DisplayName("a creator chain cannot drive Jackson into recursion")
	void creatorChainDoesNotRecurse() throws Exception {
		User a = new User();
		a.setId(1L);
		User b = new User();
		b.setId(2L);
		a.setCreatedBy(b);
		b.setCreatedBy(a); // a cycle; without @JsonIgnore this would not terminate

		assertDoesNotThrow(() -> json(a),
				"serialisation recursed or overflowed on a createdBy cycle");
	}

	@Test
	@DisplayName("Role.privileges is serialised when present, without touching Privilege.roles")
	void rolePrivilegesSerialiseWithoutBackReference() throws Exception {
		// Privilege.roles is the inverse side and is @JsonIgnore; if it were not,
		// Role -> privileges -> roles -> privileges would recurse.
		Privilege p = new Privilege();
		p.setId(1L);
		p.setName("INVOICE_READ");
		Role role = new Role();
		role.setRoleId(1L);
		role.setRoleName("ADMIN");
		role.setPrivileges(Set.of(p));
		p.setRoles(Set.of(role));

		String body = assertDoesNotThrow(() -> json(role));
		assertTrue(body.contains("INVOICE_READ"), "privileges should still be visible on a role");
		assertFalse(body.contains("\"roles\""), "the inverse side must not be serialised");
	}
}
