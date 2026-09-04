package com.invoice.serviceImpl;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.invoice.DTO.PrivilegeDTO;
import com.invoice.DTO.RoleDTO;
import com.invoice.entity.ManageUsers;
import com.invoice.entity.Privilege;
import com.invoice.entity.Role;
import com.invoice.entity.User;
import com.invoice.exception.BusinessException;
import com.invoice.repository.ManageUserRepository;
import com.invoice.repository.PrivilegeRepository;
import com.invoice.repository.RoleRepository;
import com.invoice.repository.UserRepository;
import com.invoice.service.RoleService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

@Service
public class RoleServiceImpl implements RoleService {

	@Autowired
	private RoleRepository roleRepository;

	@Autowired
	private PrivilegeRepository privilegeRepository;

	@Autowired
	private ManageUserRepository manageUserRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ManageUserRepository repository;

	@PersistenceContext
	private EntityManager entityManager;

	private static final Logger log = LoggerFactory.getLogger(RoleServiceImpl.class);

	@Override
	@org.springframework.transaction.annotation.Transactional
	public RoleDTO createRole(RoleDTO roleDTO, String loggedInEmail) {

		User currentUser = userRepository.findByEmailIgnoreCase(loggedInEmail)
				.orElseThrow(() -> new RuntimeException("Logged-in user not found"));

		// ✅ Check duplicate role for same admin
		Optional<Role> existingRole = roleRepository.findByRoleNameIgnoreCaseAndAdminId(roleDTO.getRoleName(),
				roleDTO.getAdminId());

		if (existingRole.isPresent()) {
			throw new BusinessException("Role '" + roleDTO.getRoleName() + "' already exists for this admin");
		}

		Role role = convertToEntity(roleDTO);

		// The tenant comes from the authenticated caller, never from the body.
		// convertToEntity copies dto.getAdminId() straight through, so a request
		// carrying {"adminId": 900} created a role owned by tenant 900 —
		// verified: a tenant-1001 session produced a role with admin_id = 900,
		// injected into another tenant's namespace.
		//
		// It also fixes the other half of the same bug: with no adminId in the
		// body the role was saved with admin_id = NULL, which the tenant guards
		// treat as unreachable — so a role created through the UI was invisible
		// to the person who had just created it.
		role.setAdminId(callerTenant.resolve(loggedInEmail).tenant());

		role.setAddedBy(currentUser.getId());
		role.setAddedByName(currentUser.getFullName());
		role.setCreatedDate(LocalDateTime.now());

		Role saved = roleRepository.save(role);

		return convertToDTO(saved);
	}

	@Override
	@Transactional
	public RoleDTO updateRole(Long roleId, RoleDTO roleDTO, String loggedInEmail) {
		// Tenant boundary first: this path allowed a tenant-A admin to rename
		// another tenant's role.
		assertRoleInCallersTenant(
				roleRepository.findById(roleId)
						.orElseThrow(() -> new com.invoice.exception.ResourceNotFoundException("Role not found with ID: " + roleId)),
				roleId, loggedInEmail);

		// 1️⃣ Get current logged-in user
		User currentUser = userRepository.findByEmailIgnoreCase(loggedInEmail)
				.orElseThrow(() -> new RuntimeException("Logged-in user not found: " + loggedInEmail));

		// 2️⃣ Fetch existing Role
		Role existing = roleRepository.findByIdWithPrivileges(roleId)
				.orElseThrow(() -> new RuntimeException("Role not found"));

		// 2️⃣b Reject renaming to a name already used by ANOTHER role of this admin.
		// (Edit previously had no duplicate check, so duplicates slipped through.)
		Long ownerAdminId = existing.getAdminId() != null ? existing.getAdminId() : roleDTO.getAdminId();
		roleRepository.findByRoleNameIgnoreCaseAndAdminId(roleDTO.getRoleName(), ownerAdminId)
				.filter(other -> !other.getRoleId().equals(roleId))
				.ifPresent(other -> {
					throw new BusinessException(
							"Role '" + roleDTO.getRoleName() + "' already exists for this admin");
				});

		// 3️⃣ Update role fields
		existing.setRoleName(roleDTO.getRoleName());
		existing.setDescription(roleDTO.getDescription());
		existing.setStatus(roleDTO.getStatus());

		if (existing.getAddedBy() == null) {
			existing.setAddedBy(currentUser.getId());
			existing.setAddedByName(currentUser.getFullName());
		}

		existing.setUpdatedBy(currentUser.getId());
		existing.setUpdatedByName(currentUser.getFullName());

		// 4️⃣ Save role
		Role updated = roleRepository.save(existing);

		// ❌ STEP 5 REMOVED (NO SYNC REQUIRED)

		// 5️⃣ Return DTO
		return convertToDTO(updated);
	}

	// Assign a single privilege to a role
	@Override
	@org.springframework.transaction.annotation.Transactional
	public RoleDTO assignPrivilegeToRole(Long roleId, Long privilegeId, Long creatorId) {
		Role role = roleRepository.findById(roleId).orElseThrow(() -> new RuntimeException("Role not found"));

		User creator = userRepository.findById(creatorId).orElseThrow(() -> new RuntimeException("Creator not found"));

		Privilege privilege = privilegeRepository.findById(privilegeId)
				.orElseThrow(() -> new RuntimeException("Privilege not found"));

		if (role.getPrivileges() == null) {
			role.setPrivileges(new HashSet<>());
		}

		role.getPrivileges().add(privilege);
		Role updated = roleRepository.save(role);
		return convertToDTO(updated);
	}

	// ✅ Get all roles
	/**
	 * Read-only transactional so the DTO mapping below runs inside the
	 * persistence context. {@code convertToDTO} reads {@code role.getPrivileges()},
	 * a LAZY {@code @ManyToMany}; without a transaction the entity is already
	 * detached by the time mapping starts and this throws
	 * {@code LazyInitializationException} as soon as open-in-view is disabled.
	 * The comment in {@code convertToDTO} claiming the privileges are "already
	 * loaded inside the transaction" was only ever true because OSIV kept one open.
	 */
	@Override
	@org.springframework.transaction.annotation.Transactional(readOnly = true)
	public List<RoleDTO> getAllRoles(String loggedInEmail) {
		// Tenant-scoped. This listing used to be a bare findAll, so
		// GET /auth/roles/getall returned every tenant's roles on the platform
		// — including their names and full privilege sets. Caught by
		// CrossTenantAuthorizationIT.listingIsScoped, not by hand: the endpoint
		// answered 200 either way, and only the body told the truth.
		//
		// SUPERADMIN keeps the platform-wide view, matching every other listing
		// in this service.
		com.invoice.entity.User caller = userRepository.findByEmailIgnoreCase(loggedInEmail)
				.orElseThrow(() -> new com.invoice.exception.ResourceNotFoundException(
						"Caller not found: " + loggedInEmail));
		String callerRole = caller.getRole() != null ? caller.getRole().getRoleName() : null;

		if ("SUPERADMIN".equalsIgnoreCase(callerRole)) {
			return roleRepository.findAllWithPrivileges().stream().map(this::convertToDTO)
					.collect(Collectors.toList());
		}

		Long callerTenant = manageUserRepository.findByEmailIgnoreCase(loggedInEmail)
				.map(com.invoice.entity.ManageUsers::getAdminId)
				.orElse(caller.getId());

		return roleRepository.findByAdminIdWithPrivileges(callerTenant).stream().map(this::convertToDTO)
				.collect(Collectors.toList());
	}

	// ✅ Get role by ID
	@Override
	@org.springframework.transaction.annotation.Transactional(readOnly = true)
	public RoleDTO getRoleById(Long roleId, String loggedInEmail) {
		Role role = roleRepository.findByIdWithPrivileges(roleId)
				.orElseThrow(() -> new com.invoice.exception.ResourceNotFoundException("Role not found with ID: " + roleId));
		assertRoleInCallersTenant(role, roleId, loggedInEmail);
		return convertToDTO(role);
	}

	// ✅ Update privileges of a role
	@Transactional
	@Override
	public RoleDTO updateRolePrivileges(Long roleId, Set<Long> selectedPrivilegeIds, String category,
			String loggedInEmail) {
		log.info("Updating privileges for Role ID: {} and Category: {}", roleId, category);

		Role role = roleRepository.findById(roleId)
				.orElseThrow(() -> new com.invoice.exception.ResourceNotFoundException("Role not found with ID: " + roleId));

		// Rewriting a role's privileges is privilege escalation if the role is
		// not ours. The read siblings assert this; this writer did not.
		assertRoleInCallersTenant(role, roleId, loggedInEmail);

		// Two independent things have to be true of every submitted id: it must
		// belong to the category the caller claims to be editing, and it must be
		// a privilege this tenant can see at all. The old code trusted the id
		// list outright, so a foreign id was attached verbatim.
		com.invoice.security.CallerTenant.Resolved caller = callerTenant.resolve(loggedInEmail);
		Set<Privilege> categoryPrivileges = privilegeRepository.findByCategory(category).stream()
				.filter(p -> caller.isSuperAdmin() || caller.owns(p.getAdminId()) || p.getAdminId() == null)
				.collect(Collectors.toSet());

		Map<Long, Privilege> selectable = categoryPrivileges.stream()
				.collect(Collectors.toMap(Privilege::getId, p -> p));

		Set<Long> requested = selectedPrivilegeIds == null ? Set.of() : selectedPrivilegeIds;
		Set<Long> rejected = requested.stream().filter(id -> !selectable.containsKey(id))
				.collect(Collectors.toCollection(java.util.LinkedHashSet::new));
		if (!rejected.isEmpty()) {
			// Deliberately does not say whether the id is foreign or merely in
			// another category - that distinction is a probe oracle.
			throw new BusinessException(
					"Privileges " + rejected + " are not assignable in category '" + category + "'");
		}

		Set<Privilege> currentPrivileges = new HashSet<>(role.getPrivileges());

		// Remove unchecked privileges only from this category.
		currentPrivileges.removeIf(p -> categoryPrivileges.contains(p) && !requested.contains(p.getId()));

		requested.forEach(id -> currentPrivileges.add(selectable.get(id)));

		role.setPrivileges(currentPrivileges);
		Role updatedRole = roleRepository.save(role);

		log.info("Updated privileges for Role '{}'. Total privileges now: {}", updatedRole.getRoleName(),
				updatedRole.getPrivileges().size());

		return mapToDTO(updatedRole);
	}

	@Override
	public void deleteRole(Long roleId, String loggedInEmail) {
		Role role = roleRepository.findById(roleId)
				.orElseThrow(() -> new com.invoice.exception.ResourceNotFoundException("Role not found with ID: " + roleId));
		assertRoleInCallersTenant(role, roleId, loggedInEmail);

		long assignedCount = userRepository.countByRole_RoleId(roleId);
		if (assignedCount > 0) {
			throw new BusinessException("The '" + role.getRoleName() + "' role is assigned to " + assignedCount
					+ " user(s) and cannot be deleted. Reassign or remove those users first.");
		}

		// Remove privileges association before deleting
		role.getPrivileges().clear();
		roleRepository.delete(role);
	}

	// ==============================
	// Helper Methods (DTO Mapping)
	// ==============================

	private RoleDTO convertToDTO(Role role) {

		// privileges are already loaded inside the transaction
		Set<PrivilegeDTO> privilegeDTOs = role.getPrivileges().stream().map(
				p -> new PrivilegeDTO(p.getId(), p.getName(), p.getCardType(), true, p.getStatus(), p.getCategory()))
				.collect(Collectors.toSet());

		return RoleDTO.builder().roleId(role.getRoleId()).roleName(role.getRoleName()).adminId(role.getAdminId())
				.description(role.getDescription()).status(role.getStatus()).addedBy(role.getAddedBy())
				.addedByName(role.getAddedByName()).updatedBy(role.getUpdatedBy())
				.updatedByName(role.getUpdatedByName()).createdDate(role.getCreatedDate())
				.updatedDate(role.getUpdatedDate()).privileges(privilegeDTOs).build();
	}

	private Role convertToEntity(RoleDTO dto) {
		Role role = new Role();
		role.setRoleId(dto.getRoleId());
		role.setRoleName(dto.getRoleName());
		role.setDescription(dto.getDescription());
		role.setStatus(dto.getStatus());
		role.setAdminId(dto.getAdminId());

		// Audit fields must be mapped
		role.setAddedBy(dto.getAddedBy());
		role.setAddedByName(dto.getAddedByName());
		role.setUpdatedBy(dto.getUpdatedBy());
		role.setUpdatedByName(dto.getUpdatedByName());
		role.setCreatedDate(dto.getCreatedDate());
		role.setUpdatedDate(dto.getUpdatedDate());

		// Privileges mapping
		if (dto.getPrivileges() != null) {
			Set<Privilege> privileges = dto.getPrivileges().stream()
					.map(p -> privilegeRepository.findById(p.getId())
							.orElseThrow(() -> new RuntimeException("Privilege not found with id: " + p.getId())))
					.collect(Collectors.toSet());
			role.setPrivileges(privileges);
		}

		return role;
	}

	// ✅ Alternative mapping method used after updates
	private RoleDTO mapToDTO(Role role) {
		return RoleDTO.builder().roleId(role.getRoleId()).roleName(role.getRoleName())
				.description(role.getDescription()).status(role.getStatus()).addedBy(role.getAddedBy())
				.adminId(
						role.getAdminId())
				.addedByName(role.getAddedByName()).updatedBy(role.getUpdatedBy())
				.updatedByName(role.getUpdatedByName()).createdDate(role.getCreatedDate())
				.updatedDate(role.getUpdatedDate())
				.privileges(role.getPrivileges() != null
						? role.getPrivileges().stream()
								.map(p -> PrivilegeDTO.builder().id(p.getId()).name(p.getName())
										.cardType(p.getCardType()).selected(true).status(p.getStatus())
										.category(p.getCategory()).build())
								.collect(Collectors.toSet())
						: Collections.emptySet())
				.build();
	}
	// GET /auth/roles/search used to have its own implementation here, built on
	// an unscoped findAll/searchAll — it returned every tenant's roles together
	// with their privilege names. It was a near-duplicate of the overload below,
	// which already resolves the tenant from the authenticated caller and scopes
	// correctly. Rather than scope two copies, the endpoint now calls that one,
	// and this copy is gone. Two implementations of one query is how the
	// unscoped variant survived in the first place.


	@Override
	@org.springframework.transaction.annotation.Transactional(readOnly = true)
	public List<RoleDTO> getRolesByAdminId(Long adminId) {

		// Fetch-joined: convertToDTO reads privileges for every row below.
		List<Role> roles = roleRepository.findByAdminIdWithPrivileges(adminId);

		if (roles.isEmpty()) {
			throw new RuntimeException("No roles found for this admin");
		}

		return roles.stream().map(this::convertToDTO).toList();
	}

	@Override
	@org.springframework.transaction.annotation.Transactional(readOnly = true)
	public Page<RoleDTO> searchRoles(int page, int size, String sortBy, String sortDir, String keyword,
			String loggedInEmail) {

		// ✅ Get logged-in user from manage_users
		ManageUsers admin = repository.findByEmailIgnoreCase(loggedInEmail)
				.orElseThrow(() -> new RuntimeException("Admin user not found"));

		// ✅ IMPORTANT FIX: use adminId column, not manage_users.id
		Long adminId = admin.getAdminId();

		if (adminId == null) {
			throw new RuntimeException("AdminId is missing for logged-in user");
		}

		boolean sortByUserName = "addedByName".equalsIgnoreCase(sortBy) || "updatedByName".equalsIgnoreCase(sortBy);

		Pageable pageable = PageRequest.of(page, size, sortByUserName ? Sort.by("roleId")
				: ("desc".equalsIgnoreCase(sortDir) ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending()));

		// ✅ Fetch roles by adminId
		Page<RoleDTO> dtoPage = (keyword == null || keyword.isBlank())
				? roleRepository.findByAdminId(adminId, pageable).map(this::mapToDTO)
				: roleRepository.searchByAdminId(adminId, keyword, pageable).map(this::mapToDTO);

		// ✅ Keep old custom in-memory sorting
		if (sortByUserName) {

			Comparator<RoleDTO> comparator = "addedByName".equalsIgnoreCase(sortBy)
					? Comparator.comparing(RoleDTO::getAddedByName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
					: Comparator.comparing(RoleDTO::getUpdatedByName,
							Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));

			if ("desc".equalsIgnoreCase(sortDir)) {
				comparator = comparator.reversed();
			}

			List<RoleDTO> sortedContent = dtoPage.getContent().stream().sorted(comparator).toList();

			return new PageImpl<>(sortedContent, pageable, dtoPage.getTotalElements());
		}

		return dtoPage;
	}

	/**
	 * Refuses a role outside the caller's tenant.
	 *
	 * <p>{@code Role.adminId} is the tenant owner, and the listing endpoints
	 * already scope by it ({@code findByAdminIdWithPrivileges}). The by-id
	 * endpoints did not, and the consequence was not merely disclosure: a
	 * tenant-A administrator could read another tenant's role, rename it, and
	 * delete it outright. Verified live — a role belonging to tenant 7900 was
	 * renamed to HIJACKED_BY_TENANT_A and then removed, by an administrator of
	 * tenant 7001.
	 *
	 * <p>Roles carry privileges, so this was a cross-tenant access-control
	 * mutation, not just a data leak.
	 *
	 * <p>SUPERADMIN is exempt, matching the listing behaviour. A role with no
	 * {@code adminId} is treated as unreachable rather than as shared: an
	 * unowned row must not become everybody's row.
	 */
	@Autowired
	private com.invoice.security.CallerTenant callerTenant;

	private void assertRoleInCallersTenant(Role role, Long roleId, String loggedInEmail) {
		User caller = userRepository.findByEmailIgnoreCase(loggedInEmail)
				.orElseThrow(() -> new com.invoice.exception.ResourceNotFoundException("Role not found with ID: " + roleId));

		String roleName = caller.getRole() != null ? caller.getRole().getRoleName() : null;
		if ("SUPERADMIN".equalsIgnoreCase(roleName)) {
			return;
		}

		Long callerTenant = caller.getId();
		com.invoice.entity.ManageUsers callerRecord =
				manageUserRepository.findByEmailIgnoreCase(loggedInEmail).orElse(null);
		if (callerRecord != null && callerRecord.getAdminId() != null) {
			callerTenant = callerRecord.getAdminId();
		}

		if (role.getAdminId() == null || !role.getAdminId().equals(callerTenant)) {
			log.warn("Cross-tenant role access refused: caller={} callerTenant={} roleId={} roleTenant={}",
					loggedInEmail, callerTenant, roleId, role.getAdminId());
			// Same message as a missing role: confirming that a role id exists in
			// another tenant lets a caller enumerate the platform.
			throw new com.invoice.exception.ResourceNotFoundException("Role not found with ID: " + roleId);
		}
	}

}
