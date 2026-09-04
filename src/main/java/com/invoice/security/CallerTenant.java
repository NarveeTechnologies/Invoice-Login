package com.invoice.security;

import org.springframework.stereotype.Component;

import com.invoice.entity.ManageUsers;
import com.invoice.entity.User;
import com.invoice.exception.ResourceNotFoundException;
import com.invoice.repository.ManageUserRepository;
import com.invoice.repository.UserRepository;

/**
 * Resolves the tenant and role of the authenticated caller.
 *
 * <p>Extracted once the same three lines had appeared in four services. The
 * duplication was not just noise — each copy was a place the boundary could be
 * got subtly wrong, and two of the cross-tenant defects in this service came
 * from exactly that: a rule applied in the list query and forgotten in the
 * lookup beside it.
 *
 * <p>The tenant is {@code ManageUsers.adminId}, which is what the JWT carries as
 * its {@code adminId} claim and what every scoped repository query filters on.
 * It is read from the database against the authenticated principal's email, not
 * taken from the request, so nothing a client sends can change it.
 */
@Component
public class CallerTenant {

	/** Platform-operator role. Exempt from tenant scoping by design. */
	public static final String SUPERADMIN = "SUPERADMIN";

	private final UserRepository userRepository;
	private final ManageUserRepository manageUserRepository;

	public CallerTenant(UserRepository userRepository, ManageUserRepository manageUserRepository) {
		this.userRepository = userRepository;
		this.manageUserRepository = manageUserRepository;
	}

	/**
	 * @throws ResourceNotFoundException if the principal has no account — the
	 *         same response a missing object gets, so a caller cannot use this
	 *         to probe for accounts
	 */
	public Resolved resolve(String loggedInEmail) {
		if (loggedInEmail == null || loggedInEmail.isBlank()) {
			throw new ResourceNotFoundException("Not found");
		}
		User caller = userRepository.findByEmailIgnoreCase(loggedInEmail)
				.orElseThrow(() -> new ResourceNotFoundException("Not found"));

		String role = caller.getRole() != null ? caller.getRole().getRoleName() : null;

		// Fall back to the user's own id when no ManageUsers row exists: a
		// caller with no tenant record is their own tenant, never everybody's.
		Long tenant = manageUserRepository.findByEmailIgnoreCase(loggedInEmail)
				.map(ManageUsers::getAdminId)
				.orElse(caller.getId());

		return new Resolved(caller, role, tenant);
	}

	/**
	 * @param tenant the authoritative tenant id — never accept this from a
	 *               request parameter or body
	 */
	public record Resolved(User user, String roleName, Long tenant) {

		public boolean isSuperAdmin() {
			return SUPERADMIN.equalsIgnoreCase(roleName);
		}

		/** Whether an object owned by {@code ownerTenant} is in scope. */
		public boolean owns(Long ownerTenant) {
			return isSuperAdmin() || (ownerTenant != null && ownerTenant.equals(tenant));
		}
	}
}
