package com.invoice.serviceImpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.invoice.DTO.PrivilegeDTO;
import com.invoice.entity.Privilege;
import com.invoice.entity.Role;
import com.invoice.repository.PrivilegeRepository;
import com.invoice.repository.RoleRepository;
import com.invoice.service.PrivilegeService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

@Service
@Transactional
public class PrivilegeServiceImpl implements PrivilegeService {

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private PrivilegeRepository privilegeRepository;
	private static final org.slf4j.Logger PRIVILEGE_GUARD_LOG =
			org.slf4j.LoggerFactory.getLogger(PrivilegeServiceImpl.class);

	@Autowired
	private com.invoice.repository.UserRepository privilegeGuardUserRepository;

	@Autowired
	private com.invoice.repository.ManageUserRepository privilegeGuardManageUserRepository;

	/**
	 * Refuses a privilege owned by another tenant.
	 *
	 * <p>{@code Privilege.adminId} exists, so privileges are tenant-owned by
	 * design — but the by-id endpoints never consulted it. A tenant-1001
	 * administrator could read, rename and <strong>delete</strong> a privilege
	 * belonging to tenant 900. Verified live: privilege 9900
	 * ({@code admin_id = 900}) was renamed and then removed by a caller
	 * authenticated as tenant 1001.
	 *
	 * <p>Privileges gate access, so deleting another tenant's privilege strips
	 * capability from that tenant's users and cascades their
	 * {@code role_privileges} rows away. This is the same destructive shape as
	 * the roles defect, one table over.
	 *
	 * <p>A privilege with a null {@code adminId} is treated as a shared platform
	 * definition and is writable only by SUPERADMIN: an unowned row must not
	 * become every tenant's row.
	 */
	@Autowired
	private com.invoice.security.CallerTenant privilegeCallerTenant;

	private void assertPrivilegeInCallersTenant(com.invoice.entity.Privilege privilege, Long id,
			String loggedInEmail) {
		if (loggedInEmail == null || loggedInEmail.isBlank()) {
			throw new com.invoice.exception.ResourceNotFoundException("Privilege not found with ID: " + id);
		}
		com.invoice.entity.User caller = privilegeGuardUserRepository
				.findByEmailIgnoreCase(loggedInEmail)
				.orElseThrow(() -> new com.invoice.exception.ResourceNotFoundException(
						"Privilege not found with ID: " + id));

		String roleName = caller.getRole() != null ? caller.getRole().getRoleName() : null;
		if ("SUPERADMIN".equalsIgnoreCase(roleName)) {
			return;
		}

		Long callerTenant = privilegeGuardManageUserRepository.findByEmailIgnoreCase(loggedInEmail)
				.map(com.invoice.entity.ManageUsers::getAdminId)
				.orElse(caller.getId());

		if (privilege.getAdminId() == null || !privilege.getAdminId().equals(callerTenant)) {
			PRIVILEGE_GUARD_LOG.warn("Cross-tenant privilege access refused: caller={} "
					+ "callerTenant={} privilegeId={} privilegeTenant={}",
					loggedInEmail, callerTenant, id, privilege.getAdminId());
			// Same message as a missing privilege — see ResourceNotFoundException.
			throw new com.invoice.exception.ResourceNotFoundException("Privilege not found with ID: " + id);
		}
	}


	@Autowired
	private RoleRepository roleRepository;

	@Override
	@org.springframework.transaction.annotation.Transactional
	public PrivilegeDTO createPrivilege(PrivilegeDTO dto, String loggedInEmail) {
		com.invoice.security.CallerTenant.Resolved caller = privilegeCallerTenant.resolve(loggedInEmail);

		// The owning tenant comes from the token, never from the body. Without
		// this the row was saved with a null admin_id, which made it part of
		// the shared platform catalogue: visible to every tenant, assignable by
		// every tenant, and - once the by-id routes were tenant-scoped - not
		// deletable by anyone, including the tenant that created it.
		Privilege privilege = Privilege.builder().name(dto.getName()).cardType(dto.getCardType())
				.status(dto.getStatus() == null || dto.getStatus().isBlank() ? "ACTIVE" : dto.getStatus())
				.category(dto.getCategory()).adminId(caller.tenant()).build();
		Privilege saved = privilegeRepository.save(privilege);

		// Auto-assign the new privilege to the caller's own Admin role, so the
		// tenant that added it can use it immediately.
		//
		// This used to read findAllByRoleNameIgnoreCase("Admin") with no tenant
		// filter, and the original comment said so: "every Admin role across
		// all tenants". One add-privilege call therefore granted a new
		// permission to every other tenant's administrator.
		List<Role> adminRoles = roleRepository.findAllByRoleNameIgnoreCase("Admin").stream()
				.filter(role -> caller.isSuperAdmin() || caller.owns(role.getAdminId()))
				.toList();
		for (Role role : adminRoles) {
			role.getPrivileges().add(saved);
			roleRepository.save(role);
		}

		return convertToDTO(saved);
	}

	@Override
	@org.springframework.transaction.annotation.Transactional
	public PrivilegeDTO updatePrivilege(Long id, PrivilegeDTO dto, String loggedInEmail) {
		Privilege privilege = privilegeRepository.findById(id)
				.orElseThrow(() -> new com.invoice.exception.ResourceNotFoundException(
						"Privilege not found with ID: " + id));
		assertPrivilegeInCallersTenant(privilege, id, loggedInEmail);

		privilege.setName(dto.getName());
		privilege.setCardType(dto.getCardType());
		privilege.setStatus(dto.getStatus());
		privilege.setCategory(dto.getCategory());

		Privilege updated = privilegeRepository.save(privilege);
		return convertToDTO(updated);
	}

	// Safe Delete Privilege
	@Override
	@Transactional
	public void deletePrivilege(Long id, String loggedInEmail) {
		Privilege privilege = privilegeRepository.findById(id)
				.orElseThrow(() -> new com.invoice.exception.ResourceNotFoundException(
						"Privilege not found with ID: " + id));
		// Deleting another tenant's privilege strips capability from their users
		// and cascades their role_privileges rows away.
		assertPrivilegeInCallersTenant(privilege, id, loggedInEmail);

		Set<Role> linkedRoles = new HashSet<>(privilege.getRoles());
		for (Role role : linkedRoles) {
			role.getPrivileges().remove(privilege);
			roleRepository.save(role);
		}

		entityManager.createNativeQuery("DELETE FROM role_privileges WHERE privilegeid = :pid").setParameter("pid", id)
				.executeUpdate();

		privilegeRepository.delete(privilege);
		privilegeRepository.flush();
		entityManager.clear();
	}

	@Override
	@Transactional
	/**
	 * Deletes every privilege sharing a category with the given id.
	 *
	 * <p>This is what {@code DELETE /auth/privileges/{id}} actually invokes —
	 * not {@link #deletePrivilege(Long, String)}, whose name suggests it. A
	 * single request removes a whole category, so it was the most destructive
	 * endpoint in the service and had no tenant check: a tenant-1001
	 * administrator deleted tenant 900's entire {@code TENANT900_CAT} category,
	 * and the {@code role_privileges} rows with it. Verified live.
	 *
	 * <p>Two guards now. The anchor privilege must belong to the caller's
	 * tenant, and the deletion is scoped to that tenant as well — a category
	 * name is not tenant-unique, so checking only the anchor would still let one
	 * tenant wipe another's privileges that happen to share a category name.
	 */
	public void deletePrivilegesByCategoryId(Long categoryId, String loggedInEmail) {
		Privilege anchor = privilegeRepository.findById(categoryId)
				.orElseThrow(() -> new com.invoice.exception.ResourceNotFoundException(
						"Privilege not found with ID: " + categoryId));
		assertPrivilegeInCallersTenant(anchor, categoryId, loggedInEmail);

		String category = anchor.getCategory();
		Long tenant = anchor.getAdminId();

		try {
			// Scoped to the anchor's tenant, not the whole category.
			List<Long> privilegeIds = entityManager
					.createQuery("SELECT p.id FROM Privilege p WHERE p.category = :c AND p.adminId = :t",
							Long.class)
					.setParameter("c", category).setParameter("t", tenant).getResultList();

			if (privilegeIds.isEmpty()) {
				throw new com.invoice.exception.ResourceNotFoundException(
						"No privileges found for category: " + category);
			}

			entityManager.createNativeQuery("DELETE FROM role_privileges WHERE privilegeid IN (:ids)")
					.setParameter("ids", privilegeIds).executeUpdate();

			entityManager.createQuery("DELETE FROM Privilege p WHERE p.id IN :ids")
					.setParameter("ids", privilegeIds).executeUpdate();

			entityManager.clear();

		} catch (com.invoice.exception.ResourceNotFoundException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException("Error deleting privileges for categoryId " + categoryId + ": " + e.getMessage(),
					e);
		}
	}

	@Override
	@org.springframework.transaction.annotation.Transactional(readOnly = true)
	public List<PrivilegeDTO> getAllPrivileges() {
		return privilegeRepository.findAll().stream().map(this::convertToDTO).collect(Collectors.toList());
	}

	@Override
	@org.springframework.transaction.annotation.Transactional(readOnly = true)
	public PrivilegeDTO getPrivilegeById(Long id, String loggedInEmail) {
		Privilege privilege = privilegeRepository.findById(id)
				.orElseThrow(() -> new com.invoice.exception.ResourceNotFoundException(
						"Privilege not found with ID: " + id));
		assertPrivilegeInCallersTenant(privilege, id, loggedInEmail);
		return convertToDTO(privilege);
	}

	@Override
	@org.springframework.transaction.annotation.Transactional(readOnly = true)
	public List<PrivilegeDTO> getPrivilegesByCategory(String category) {
		return privilegeRepository.findByCategoryIgnoreCase(category).stream().map(this::convertToDTO)
				.collect(Collectors.toList());
	}

	@Override
	@org.springframework.transaction.annotation.Transactional(readOnly = true)
	public Map<String, List<PrivilegeDTO>> getAllPrivilegesGrouped(String loggedInEmail) {
		// Tenant-scoped: own privileges plus the shared (null-owner) catalogue.
		// A bare findAll() here exposed other tenants' custom privileges.
		com.invoice.security.CallerTenant.Resolved caller = privilegeCallerTenant.resolve(loggedInEmail);
		List<Privilege> privileges = caller.isSuperAdmin()
				? privilegeRepository.findAll()
				: privilegeRepository.findVisibleToTenant(caller.tenant());

		return privileges.stream().collect(Collectors.groupingBy(Privilege::getCategory,
				Collectors.mapping(this::convertToDTO, Collectors.toList())));
	}

	@Override
	@org.springframework.transaction.annotation.Transactional(readOnly = true)
	public Map<String, List<PrivilegeDTO>> getPrivilegesByRole(Long roleId, String loggedInEmail) {
		com.invoice.security.CallerTenant.Resolved caller = privilegeCallerTenant.resolve(loggedInEmail);

		// The role itself is a tenant-owned object. Reading its privilege
		// composition discloses how another tenant configures access, so the
		// role must be checked before anything is grouped. 404, not 403: the
		// existence of the id is itself not ours to confirm.
		Role role = roleRepository.findById(roleId)
				.orElseThrow(() -> new com.invoice.exception.ResourceNotFoundException(
						"Role not found with ID: " + roleId));
		assertRoleVisibleToCaller(role, roleId, caller);

		// The catalogue is scoped the same way /privileges/getall is (T-9).
		// Listing every tenant's privilege names here would reintroduce the
		// same disclosure through the neighbouring query.
		List<Privilege> visible = caller.isSuperAdmin()
				? privilegeRepository.findAll()
				: privilegeRepository.findVisibleToTenant(caller.tenant());

		Set<Privilege> assigned = new HashSet<>(role.getPrivileges());

		Map<String, List<PrivilegeDTO>> grouped = new HashMap<>();
		visible.forEach(privilege -> grouped
				.computeIfAbsent(privilege.getCategory(), c -> new ArrayList<>())
				.add(PrivilegeDTO.builder().id(privilege.getId()).name(privilege.getName())
						.cardType(privilege.getCardType()).selected(assigned.contains(privilege))
						.status(privilege.getStatus()).category(privilege.getCategory()).build()));

		return grouped;
	}

	private void assertRoleVisibleToCaller(Role role, Long roleId,
			com.invoice.security.CallerTenant.Resolved caller) {
		if (caller.isSuperAdmin()) {
			return;
		}
		if (!caller.owns(role.getAdminId())) {
			throw new com.invoice.exception.ResourceNotFoundException("Role not found with ID: " + roleId);
		}
	}

	@Override
	@org.springframework.transaction.annotation.Transactional(readOnly = true)
	public Map<String, String> getEndpointPrivilegesMap() {
		return Collections.emptyMap(); // implement later if needed
	}

	private PrivilegeDTO convertToDTO(Privilege privilege) {
		return PrivilegeDTO.builder().id(privilege.getId()).name(privilege.getName()).cardType(privilege.getCardType())
				.selected(false).status(privilege.getStatus()).category(privilege.getCategory()).build();
	}

}
