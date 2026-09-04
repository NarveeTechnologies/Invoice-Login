package com.invoice.serviceImpl;

import java.io.IOException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.invoice.DTO.ManageUserDTO;
import com.invoice.DTO.SortingRequestDTO;
import com.invoice.DTO.UserUpdateRequest;
import com.invoice.entity.AuditLog;
import com.invoice.entity.BankDetails;
import com.invoice.entity.ManageUsers;
import com.invoice.entity.Role;
import com.invoice.entity.User;
import com.invoice.exception.BusinessException;
import com.invoice.repository.AuditLogRepository;
import com.invoice.repository.ManageUserRepository;
import com.invoice.repository.RoleRepository;
import com.invoice.repository.UserRepository;
import com.invoice.service.EmailService;
import com.invoice.service.ManageUserService;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class ManageUsersServiceImpl implements ManageUserService {

	@Value("${file.upload-dir}")
	private String uploadDir;

	@Autowired
	private EmailService emailService;

	@Autowired
	private UserNameSyncServiceImpl userNameSyncServiceImpl;

	@Autowired
	private ManageUserRepository manageUserRepository;

	@Autowired
	private UserRepository userRepository;
	private final RoleRepository roleRepository;
	private final AuditLogRepository auditLogRepository;

	private static final Logger log = LoggerFactory.getLogger(ManageUsersServiceImpl.class);

	/** ================= FETCH LOGGED-IN USER ================= **/
	private User getCurrentLoggedInUser(String email) {
		return userRepository.findByEmailIgnoreCase(email)
				.orElseThrow(() -> new RuntimeException("Logged-in user not found: " + email));
	}

	/** ================= CONVERT ENTITY TO DTO ================= **/
	private ManageUserDTO convertToDTO(ManageUsers entity) {

		String fullName = entity.getFullName() != null ? entity.getFullName().trim().replaceAll("\\s+", " ")
				: buildFullName(entity);

		return ManageUserDTO.builder().id(entity.getId()).fullName(fullName).firstName(entity.getFirstName())
				.middleName(entity.getMiddleName()).lastName(entity.getLastName()).email(entity.getEmail())
				.primaryEmail(entity.getPrimaryEmail()).companyName(entity.getCompanyName())
				.roleName(entity.getRole() != null ? entity.getRole().getRoleName() : null)
				.addedBy(entity.getAddedBy() != null ? entity.getAddedBy().getId().toString() : null)
				.addedByName(entity.getAddedByName()).updatedBy(entity.getUpdatedBy())
				.updatedByName(entity.getUpdatedByName()).state(entity.getState()).country(entity.getCountry())
				.pincode(entity.getPincode()).loginUrl(entity.getLoginUrl()).telephone(entity.getTelephone())
				.ein(entity.getEin()).gstin(entity.getGstin()).website(entity.getWebsite()).address(entity.getAddress())
				.city(entity.getCity()).fid(entity.getFid()).everifyId(entity.getEverifyId())
				.dunsNumber(entity.getDunsNumber()).stateOfIncorporation(entity.getStateOfIncorporation())
				.naicsCode(entity.getNaicsCode()).signingAuthorityName(entity.getSigningAuthorityName())
				.designation(entity.getDesignation()).dateOfIncorporation(entity.getDateOfIncorporation())
				.businessCountry(entity.getBusinessCountry()).BankDetails(entity.getBankDetails()).build();
	}

	/** ================= BUILD FULL NAME ================= **/
	private String buildFullName(ManageUsers user) {
		return Stream.of(user.getFirstName(), user.getMiddleName(), user.getLastName())
				.filter(s -> s != null && !s.isBlank()).collect(Collectors.joining(" "));
	}

	private String buildFullName(User user) {
		return Stream.of(user.getFirstName(), user.getMiddleName(), user.getLastName())
				.filter(s -> s != null && !s.isBlank()).collect(Collectors.joining(" "));
	}

	/** Null-safe, trim + case-insensitive equality (treats null and blank as equal). */
	private static boolean equalsTrimIgnoreCase(String a, String b) {
		String x = a == null ? "" : a.trim();
		String y = b == null ? "" : b.trim();
		return x.equalsIgnoreCase(y);
	}

	private String extractDomain(String email) {
		if (email == null || !email.contains("@")) {
			throw new RuntimeException("Invalid email address");
		}
		return email.substring(email.indexOf("@") + 1).toLowerCase();
	}

	/** ================= CREATE USER ================= **/

	@Override
	@Transactional
	public ManageUserDTO createUser(ManageUsers manageUsers, String loggedInEmail) {

		// 1️⃣ Get logged-in user
		User currentUser = getCurrentLoggedInUser(loggedInEmail);

		if (currentUser.getRole() == null || currentUser.getRole().getRoleName() == null) {
			throw new BusinessException("Logged-in user role not found");
		}

		String currentUserRole = currentUser.getRole().getRoleName().toUpperCase();

		// ✅ REMOVED: hardcoded role check — access is now controlled via privileges
		// ONLY keep SUPERADMIN protection
		if ("ADMIN".equals(currentUserRole) && "SUPERADMIN".equalsIgnoreCase(manageUsers.getRoleName())) {
			throw new BusinessException("ADMIN cannot create SUPERADMIN");
		}

		// 2️⃣ Normalize Email
		// 2️⃣ Normalize Email
		String newUserEmail = manageUsers.getEmail().trim().toLowerCase();
		manageUsers.setEmail(newUserEmail);

		// ✅ Check for duplicate email in manage_users
		if (manageUserRepository.existsByEmailIgnoreCase(newUserEmail)) {
			throw new BusinessException("User with email '" + newUserEmail + "' already exists.");
		}

		// ✅ Check for duplicate full name (first + last) within the same company.
		String firstName = manageUsers.getFirstName() != null ? manageUsers.getFirstName().trim() : null;
		String lastName = manageUsers.getLastName() != null ? manageUsers.getLastName().trim() : null;
		if (firstName != null && !firstName.isEmpty() && lastName != null && !lastName.isEmpty()
				&& manageUsers.getAdminId() != null
				&& manageUserRepository.existsByFirstNameIgnoreCaseAndLastNameIgnoreCaseAndAdminId(
						firstName, lastName, manageUsers.getAdminId())) {
			throw new BusinessException(
					"A user named '" + firstName + " " + lastName + "' already exists in this company.");
		}

		// 3️⃣ Check Company Domain

		// 3️⃣ Check Company Domain
		String currentDomain = extractDomain(currentUser.getEmail());
		String newUserDomain = extractDomain(newUserEmail);

		if (!currentDomain.equalsIgnoreCase(newUserDomain)) {
			throw new BusinessException("You can create users only for your own company");
		}

		manageUsers.setCompanyDomain(currentDomain);

		// 4️⃣ Fetch Role (Single Query)
//	    Role role = roleRepository.findByRoleNameIgnoreCase(manageUsers.getRoleName())
//	            .orElseThrow(() -> new BusinessException("Role not found: " + manageUsers.getRoleName()));

		Role role = roleRepository.findById(manageUsers.getRole().getRoleId()).orElseThrow(
				() -> new BusinessException("Role not found for id: " + manageUsers.getRole().getRoleId()));

		manageUsers.setRole(role);
		manageUsers.setRoleName(role.getRoleName());

		// 5️⃣ Trim Full Name
		if (manageUsers.getFullName() != null) {
			manageUsers.setFullName(manageUsers.getFullName().trim().replaceAll("\\s+", " "));
		}

		manageUsers.setAddedBy(currentUser);
		manageUsers.setCreatedBy(currentUser);
		manageUsers.setAddedByName(buildFullName(currentUser));

		// 6️⃣ Save ManageUsers
		ManageUsers savedManageUser = manageUserRepository.save(manageUsers);

		// 7️⃣ Check if User already exists (Single Query)
		Optional<User> existingUserOpt = userRepository.findByEmailIgnoreCase(newUserEmail);

		if (existingUserOpt.isPresent()) {

			// 🔹 Update Existing User
			User existingUser = existingUserOpt.get();

			// Backfill for a row that predates this fix or was created by
			// another path. Only ever set, never changed to a different tenant:
			// silently re-homing an existing user would move which schema their
			// data comes from.
			if (!StringUtils.hasText(existingUser.getCompanyDomain())) {
				existingUser.setCompanyDomain(savedManageUser.getCompanyDomain());
			}

			if (existingUser.getCreatedBy() == null) {
				existingUser.setCreatedBy(currentUser);
			}

			existingUser.setRole(role);
			existingUser.setFullName(savedManageUser.getFullName());
			existingUser.setPrimaryEmail(savedManageUser.getPrimaryEmail());
			existingUser.setActive(true);
			existingUser.setApproved(true);

			userRepository.save(existingUser);

		} else {

			// 🔹 Create New User
			User user = new User();

			user.setEmail(savedManageUser.getEmail());
			// The tenant key. Without it this user_info row has a null
			// company_domain, so JwtServiceImpl omits the companyDomain claim,
			// TenantFilter sets no schema, and TenantRoutingDataSource falls
			// back to the DEFAULT datasource. Every tenant's sub-users then
			// share one schema, where the customer service's unscoped vendor
			// queries see -- and can delete -- each other's rows. The value was
			// already resolved and written to manage_users above; it was simply
			// not copied here.
			user.setCompanyDomain(savedManageUser.getCompanyDomain());
			user.setFirstName(savedManageUser.getFirstName());
			user.setMiddleName(savedManageUser.getMiddleName());
			user.setLastName(savedManageUser.getLastName());
			user.setFullName(savedManageUser.getFullName());
			user.setPrimaryEmail(savedManageUser.getPrimaryEmail());

			user.setCompanyName(savedManageUser.getCompanyName());
			user.setMobileNumber(savedManageUser.getMobileNumber());
			user.setState(savedManageUser.getState());
			user.setCountry(savedManageUser.getCountry());
			user.setCity(savedManageUser.getCity());
			user.setPincode(savedManageUser.getPincode());
			user.setTelephone(savedManageUser.getTelephone());
			user.setWebsite(savedManageUser.getWebsite());
			user.setEin(savedManageUser.getEin());
			user.setAddress(savedManageUser.getAddress());
			user.setLoginUrl(savedManageUser.getLoginUrl());

			user.setFid(savedManageUser.getFid());
			user.setEverifyId(savedManageUser.getEverifyId());
			user.setDunsNumber(savedManageUser.getDunsNumber());
			user.setStateOfIncorporation(savedManageUser.getStateOfIncorporation());
			user.setNaicsCode(savedManageUser.getNaicsCode());
			user.setSigningAuthorityName(savedManageUser.getSigningAuthorityName());
			user.setDesignation(savedManageUser.getDesignation());
			user.setDateOfIncorporation(savedManageUser.getDateOfIncorporation());
			user.setBusinessCountry(savedManageUser.getBusinessCountry());

			user.setBankDetails(savedManageUser.getBankDetails());

			user.setApproved(true);
			user.setActive(true);
			user.setCreatedBy(currentUser);
			user.setRole(role);

			userRepository.save(user);
		}

		// 🔹 Send Registration Email
		try {
			emailService.sendRegistrationEmail(savedManageUser.getEmail(), savedManageUser.getFullName(),
					savedManageUser.getRoleName());
		} catch (Exception e) {
			log.error("Error sending registration email: {}", e.getMessage());
		}

		return convertToDTO(savedManageUser);
	}

	/** ================= UPDATE USER PROFILE ================= **/
	@Override
	public User updateUserProfile(UserUpdateRequest request, String loggedInEmail) {
		// ✅ Get the currently logged-in user
		User currentUser = getCurrentLoggedInUser(loggedInEmail);

		// ✅ Admins can update any user, normal users can only update their own
		User userToUpdate;

		boolean isAdmin = currentUser.getRole() != null
				&& List.of("SUPERADMIN", "ADMIN").contains(currentUser.getRole().getRoleName().toUpperCase());

		if (isAdmin && request.getId() != null && request.getId() > 0) {
			// Admin updating another user
			userToUpdate = userRepository.findById(request.getId())
					.orElseThrow(() -> new RuntimeException("User not found with id: " + request.getId()));
		} else {
			// Normal user updating their own profile
			userToUpdate = userRepository.findByEmailIgnoreCase(loggedInEmail)
					.orElseThrow(() -> new RuntimeException("Logged-in user not found: " + loggedInEmail));
		}

		// ✅ Update editable fields
		userToUpdate.setFullName(request.getFullName());
		userToUpdate.setPrimaryEmail(request.getPrimaryEmail());
		userToUpdate.setAlternativeEmail(request.getAlternativeEmail());
		userToUpdate.setMobileNumber(request.getMobileNumber());
		userToUpdate.setAlternativeMobileNumber(request.getAlternativeMobileNumber());
		userToUpdate.setTaxId(request.getTaxId());
		userToUpdate.setBusinessId(request.getBusinessId());
		userToUpdate.setPreferredCurrency(request.getPreferredCurrency());
		userToUpdate.setInvoicePrefix(request.getInvoicePrefix());
		userToUpdate.setCompanyName(request.getCompanyName());

		// ✅ Save update
		User updatedUser = userRepository.save(userToUpdate);

		// ✅ Update manage_users table audit fields if applicable
		manageUserRepository.findByEmailIgnoreCase(updatedUser.getEmail()).ifPresent(manageUser -> {
			manageUser.setUpdatedBy(currentUser.getId());
			manageUser.setUpdatedByName(buildFullName(currentUser));
			manageUserRepository.save(manageUser);
		});

		return updatedUser;
	}

	/** ================= UPDATE USER ================= **/

	@Override
	@Transactional
	public ManageUserDTO updateUser(Long id, ManageUsers manageUsers, String loggedInEmail) {

		// ---------------- 1️⃣ Get current logged-in user ----------------
		User currentUser = getCurrentLoggedInUser(loggedInEmail);

		// ---------------- 2️⃣ Fetch existing ManageUsers ----------------
		ManageUsers existing = manageUserRepository.findById(id)
				.orElseThrow(() -> new com.invoice.exception.ResourceNotFoundException("User not found with ID: " + id));

		// This method previously had no authorization check of any kind — not
		// tenant, not role. Any authenticated caller could modify any user on the
		// platform by changing the id in the URL.
		assertSameTenant(currentUser, existing, id);

		String oldFullName = existing.getFullName();
		String oldFirstName = existing.getFirstName();
		String oldLastName = existing.getLastName();

		// ---------------- 3️⃣ Handle name updates ----------------
		if (manageUsers.getFullName() != null && !manageUsers.getFullName().isBlank()) {

			String[] parts = manageUsers.getFullName().trim().split("\\s+");
			existing.setFirstName(parts[0]);
			existing.setMiddleName(
					parts.length > 2 ? String.join(" ", Arrays.copyOfRange(parts, 1, parts.length - 1)) : null);
			existing.setLastName(parts.length > 1 ? parts[parts.length - 1] : null);

		} else {
			if (manageUsers.getFirstName() != null)
				existing.setFirstName(manageUsers.getFirstName());
			if (manageUsers.getMiddleName() != null)
				existing.setMiddleName(manageUsers.getMiddleName());
			if (manageUsers.getLastName() != null)
				existing.setLastName(manageUsers.getLastName());
		}

		existing.setFullName(buildFullName(existing));

		// Prevent renaming to a first+last name already used by ANOTHER user in the same
		// company. Only enforced when the name actually changes, so editing other fields
		// of an existing user is never blocked. Uses an exists-query that excludes this
		// user's own id (safe even if duplicate names already exist in the data).
		String updFirstName = existing.getFirstName() != null ? existing.getFirstName().trim() : null;
		String updLastName = existing.getLastName() != null ? existing.getLastName().trim() : null;
		boolean nameChanged = !equalsTrimIgnoreCase(oldFirstName, updFirstName)
				|| !equalsTrimIgnoreCase(oldLastName, updLastName);
		if (nameChanged && updFirstName != null && !updFirstName.isEmpty() && updLastName != null
				&& !updLastName.isEmpty() && existing.getAdminId() != null
				&& manageUserRepository.existsByFirstNameIgnoreCaseAndLastNameIgnoreCaseAndAdminIdAndIdNot(
						updFirstName, updLastName, existing.getAdminId(), id)) {
			throw new BusinessException(
					"A user named '" + updFirstName + " " + updLastName + "' already exists in this company.");
		}

		// ---------------- 4️⃣ Core fields ----------------
		if (manageUsers.getEmail() != null && !manageUsers.getEmail().isBlank()) {
			existing.setEmail(manageUsers.getEmail());
		}

		if (manageUsers.getPrimaryEmail() != null && !manageUsers.getPrimaryEmail().isBlank()) {
			existing.setPrimaryEmail(manageUsers.getPrimaryEmail());
		}

		if (manageUsers.getMobileNumber() != null && !manageUsers.getMobileNumber().isBlank()) {
			existing.setMobileNumber(manageUsers.getMobileNumber());
		}

		if (manageUsers.getCompanyName() != null && !manageUsers.getCompanyName().isBlank()) {
			existing.setCompanyName(manageUsers.getCompanyName());
		}

		// ---------------- 5️⃣ Newly added fields ----------------
		if (manageUsers.getState() != null)
			existing.setState(manageUsers.getState());
		if (manageUsers.getCountry() != null)
			existing.setCountry(manageUsers.getCountry());
		if (manageUsers.getPincode() != null)
			existing.setPincode(manageUsers.getPincode());

		if (manageUsers.getCity() != null)
			existing.setCity(manageUsers.getCity());

		if (manageUsers.getTelephone() != null)
			existing.setTelephone(manageUsers.getTelephone());
		if (manageUsers.getEin() != null)
			existing.setEin(manageUsers.getEin());
		if (manageUsers.getGstin() != null)
			existing.setGstin(manageUsers.getGstin());
		if (manageUsers.getWebsite() != null)
			existing.setWebsite(manageUsers.getWebsite());
		if (manageUsers.getAddress() != null)
			existing.setAddress(manageUsers.getAddress());

		// ---------------- 6️⃣ Newly Added Additional Fields ----------------

		if (manageUsers.getFid() != null)
			existing.setFid(manageUsers.getFid());

		if (manageUsers.getEverifyId() != null)
			existing.setEverifyId(manageUsers.getEverifyId());

		if (manageUsers.getDunsNumber() != null)
			existing.setDunsNumber(manageUsers.getDunsNumber());

		if (manageUsers.getStateOfIncorporation() != null)
			existing.setStateOfIncorporation(manageUsers.getStateOfIncorporation());

		if (manageUsers.getNaicsCode() != null)
			existing.setNaicsCode(manageUsers.getNaicsCode());

		if (manageUsers.getSigningAuthorityName() != null)
			existing.setSigningAuthorityName(manageUsers.getSigningAuthorityName());

		if (manageUsers.getDesignation() != null)
			existing.setDesignation(manageUsers.getDesignation());

		if (manageUsers.getDateOfIncorporation() != null)
			existing.setDateOfIncorporation(manageUsers.getDateOfIncorporation());

		// ✅ Bank Details (Only if not null & not empty)
		if (manageUsers.getBankDetails() != null && !manageUsers.getBankDetails().isEmpty())
			existing.setBankDetails(manageUsers.getBankDetails());

		// ---------------- 6️⃣ Handle role updates safely ----------------
		Role role = null;
		if (manageUsers.getRole() != null && manageUsers.getRole().getRoleId() != null) {

			role = roleRepository.findById(manageUsers.getRole().getRoleId()).orElseThrow(
					() -> new BusinessException("Role not found for id: " + manageUsers.getRole().getRoleId()));

			existing.setRole(role);

			// Optional: sync roleName field if you still store it
			existing.setRoleName(role.getRoleName());
		}
		// ❌ DO NOT reset role if not provided

		// ---------------- 7️⃣ Audit fields ----------------
		existing.setUpdatedBy(currentUser.getId());
		existing.setUpdatedByName(buildFullName(currentUser));
		// existing.setUpdatedOn(LocalDateTime.now());

		// ---------------- 8️⃣ Save ManageUsers ----------------
		ManageUsers saved = manageUserRepository.save(existing);

		// ---------------- 9️⃣ Sync User table (NO new record) ----------------
		User user = userRepository.findByEmailIgnoreCase(saved.getEmail())
				.orElseThrow(() -> new RuntimeException("Linked User not found"));

		user.setFirstName(saved.getFirstName());
		user.setMiddleName(saved.getMiddleName());
		user.setLastName(saved.getLastName());
		user.setFullName(saved.getFullName());
		user.setEmail(saved.getEmail());
		user.setPrimaryEmail(saved.getEmail());

		if (role != null) {
			user.setRole(role);
		}

		userRepository.save(user);

		// ---------------- 🔟 Sync username if changed ----------------
		if (!Objects.equals(oldFullName, saved.getFullName())) {
			userNameSyncServiceImpl.syncUserFullName(user.getId(), saved.getFullName());
		}

		// ---------------- 1️⃣1️⃣ Audit log ----------------
		auditLogRepository.save(AuditLog.builder().action("UPDATE").entityName("ManageUsers").entityId(saved.getId())
				.performedBy(buildFullName(currentUser)).performedById(currentUser.getId())
				.email(currentUser.getEmail()).timestamp(LocalDateTime.now())
				.details("Updated ManageUser ID: " + saved.getId()).build());
		// ---------------- 1️⃣2️⃣ Return DTO ----------------
		return convertToDTO(saved);
	}

	/** ================= DELETE USER ================= **/
	@Override
	public void deleteUser(Long id, String loggedInEmail) {
		User currentUser = getCurrentLoggedInUser(loggedInEmail);
		ManageUsers manageUser = manageUserRepository.findById(id)
				.orElseThrow(() -> new RuntimeException("User not found"));

		// Tenant boundary before any role logic: deleting another tenant's user is
		// worse than reading one, and this path had no domain check at all.
		assertSameTenant(currentUser, manageUser, id);

		if ("ADMIN".equalsIgnoreCase(currentUser.getRole().getRoleName())
				&& "SUPERADMIN".equalsIgnoreCase(manageUser.getRoleName())) {
			throw new RuntimeException("ADMIN cannot delete SUPERADMIN");
		}

		boolean hasDeletePrivilege = currentUser.getRole().getPrivileges().stream()
				.anyMatch(p -> "DELETE_MANAGE_USERS".equalsIgnoreCase(p.getName()));

		if (!hasDeletePrivilege) {
			throw new RuntimeException("You do not have DELETE_MANAGE_USERS privilege");
		}

		userRepository.findByEmailIgnoreCase(manageUser.getEmail()).ifPresent(userRepository::delete);

		manageUserRepository.deleteById(id);
	}

	/** ================= GET ALL USERS ================= **/
//	@Override
//	public List<ManageUserDTO> getAllUsers(String loggedInEmail) {
//		User currentUser = getCurrentLoggedInUser(loggedInEmail);
//		String roleName = currentUser.getRole() != null ? currentUser.getRole().getRoleName() : null;
//
//		List<ManageUsers> users;
//		if ("SUPERADMIN".equalsIgnoreCase(roleName)) {
//			users = manageUserRepository.findAll();
//		} else if ("ADMIN".equalsIgnoreCase(roleName)) {
//			users = manageUserRepository.findAll().stream().filter(u -> !"SUPERADMIN".equalsIgnoreCase(u.getRoleName()))
//					.collect(Collectors.toList());
//		} else {
//			users = manageUserRepository.findByEmailIgnoreCase(currentUser.getEmail()).map(List::of)
//					.orElse(Collections.emptyList());
//		}
//
//		return users.stream().map(this::convertToDTO).collect(Collectors.toList());
//	}

	@Override
	public List<ManageUserDTO> getAllUsers(String loggedInEmail) {

		User currentUser = getCurrentLoggedInUser(loggedInEmail);

		String roleName = currentUser.getRole() != null ? currentUser.getRole().getRoleName() : null;

		String domain = extractDomain(currentUser.getEmail());

		List<ManageUsers> users;

		if ("SUPERADMIN".equalsIgnoreCase(roleName)) {
			// Superadmin can see everything
			users = manageUserRepository.findAll();

		} else if ("ADMIN".equalsIgnoreCase(roleName)) {
			// ADMIN sees only their domain users
			users = manageUserRepository.findByCompanyDomainIgnoreCase(domain);

		} else {
			// ✅ All other roles (HR, ACCOUNTANT etc.) — see their own company's users
			users = manageUserRepository.findByCompanyDomainIgnoreCase(domain);
		}

		return users.stream().map(this::convertToDTO).collect(Collectors.toList());
	}

	/** ================= GET BY ID ================= **/
	@Override
	public ManageUserDTO getById(Long id) {
		ManageUsers entity = manageUserRepository.findById(id)
				.orElseThrow(() -> new RuntimeException("User not found"));
		return convertToDTO(entity);
	}

	/** ================= GET BY EMAIL ================= **/
	@Override
	public ManageUserDTO getByEmail(String email) {
		ManageUsers entity = manageUserRepository.findByEmailIgnoreCase(email)
				.orElseThrow(() -> new RuntimeException("User not found with email: " + email));
		return convertToDTO(entity);
	}

	/**
	 * ================= PAGINATION + SEARCH (FIXED ALPHABETICAL) =================
	 **/
	@Override
	public Page<ManageUserDTO> getAllUsersWithPaginationAndSearch(int page, int size, String sortField, String sortDir,
			String keyword, Long adminId) {

		if (!"asc".equalsIgnoreCase(sortDir) && !"desc".equalsIgnoreCase(sortDir)) {
			sortDir = "asc";
		}

		// Map external sortField names to entity fields
		Map<String, String> sortFieldMap2 = Map.of("id", "id", "firstName", "firstName", "middleName", "middleName",
				"lastName", "lastName", "fullName", "fullName", "email", "email", "primaryEmail", "primaryEmail",
				"roleName", "roleName", "addedByName", "addedByName", "updatedByName", "updatedByName");

		String mappedSortField2 = sortFieldMap2.getOrDefault(sortField, "id");

		final String kw = keyword;
		Specification<ManageUsers> spec = (root, query, cb) -> {
			List<jakarta.persistence.criteria.Predicate> all = new ArrayList<>();
			if (adminId != null) {
				all.add(cb.equal(root.get("adminId"), adminId));
			}
			if (kw != null && !kw.trim().isEmpty()) {
				String like = "%" + kw.trim().toLowerCase() + "%";
				List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
				predicates.add(cb.like(cb.lower(root.get("firstName")), like));
				predicates.add(cb.like(cb.lower(root.get("middleName")), like));
				predicates.add(cb.like(cb.lower(root.get("lastName")), like));
				predicates.add(cb.like(cb.lower(root.get("fullName")), like));
				predicates.add(cb.like(cb.lower(root.get("email")), like));
				predicates.add(cb.like(cb.lower(root.get("primaryEmail")), like));
				predicates.add(cb.like(cb.lower(root.get("roleName")), like));
				predicates.add(cb.like(cb.lower(root.get("addedByName")), like));
				predicates.add(cb.like(cb.lower(root.get("updatedByName")), like));
				all.add(cb.or(predicates.toArray(new jakarta.persistence.criteria.Predicate[0])));
			}
			if (all.isEmpty()) return cb.conjunction();
			return cb.and(all.toArray(new jakarta.persistence.criteria.Predicate[0]));
		};

		Sort sortSpec = Sort.by(sortDir.equalsIgnoreCase("desc") ? Sort.Order.desc(mappedSortField2).ignoreCase()
				: Sort.Order.asc(mappedSortField2).ignoreCase());

		Pageable pageable = PageRequest.of(page, size, sortSpec);
		Page<ManageUsers> userPage = manageUserRepository.findAll(spec, pageable);
		List<ManageUserDTO> dtoList = userPage.getContent().stream().map(this::convertToDTO).toList();
		return new PageImpl<>(dtoList, pageable, userPage.getTotalElements());
	}

	@Override
	public Page<ManageUserDTO> getAllUsersWithPaginationAndSearch(int page, int size, String sortField, String sortDir,
			String keyword) {

		if (!"asc".equalsIgnoreCase(sortDir) && !"desc".equalsIgnoreCase(sortDir)) {
			sortDir = "asc";
		}

		// Map external sortField names to entity fields
		Map<String, String> sortFieldMap = Map.of("id", "id", "firstName", "firstName", "middleName", "middleName",
				"lastName", "lastName", "fullName", "fullName", "email", "email", "primaryEmail", "primaryEmail",
				"roleName", "roleName", "addedByName", "addedByName", "updatedByName", "updatedByName");

		String mappedSortField = sortFieldMap.getOrDefault(sortField, "id");

		// Specification for keyword search
		Specification<ManageUsers> spec = (root, query, cb) -> {
			if (keyword == null || keyword.trim().isEmpty()) {
				return cb.conjunction();
			}

			String like = "%" + keyword.trim().toLowerCase() + "%";
			List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();

			predicates.add(cb.like(cb.lower(root.get("firstName")), like));
			predicates.add(cb.like(cb.lower(root.get("middleName")), like));
			predicates.add(cb.like(cb.lower(root.get("lastName")), like));
			predicates.add(cb.like(cb.lower(root.get("fullName")), like));
			predicates.add(cb.like(cb.lower(root.get("email")), like));
			predicates.add(cb.like(cb.lower(root.get("primaryEmail")), like));
			predicates.add(cb.like(cb.lower(root.get("roleName")), like));
			predicates.add(cb.like(cb.lower(root.get("addedByName")), like));
			predicates.add(cb.like(cb.lower(root.get("updatedByName")), like));

			return cb.or(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
		};

		// Sort case-insensitive
		Sort sort = Sort.by(sortDir.equalsIgnoreCase("desc") ? Sort.Order.desc(mappedSortField).ignoreCase()
				: Sort.Order.asc(mappedSortField).ignoreCase());

		Pageable pageable = PageRequest.of(page, size, sort);

		Page<ManageUsers> userPage = manageUserRepository.findAll(spec, pageable);

		List<ManageUserDTO> dtoList = userPage.getContent().stream().map(this::convertToDTO).toList();

		return new PageImpl<>(dtoList, pageable, userPage.getTotalElements());
	}

	/** ================= UPDATE USER PROFILE ================= **/
	@Override
	public User updateUserProfile(UserUpdateRequest request, MultipartFile profileImage, String loggedInEmail) {
		User currentUser = getCurrentLoggedInUser(loggedInEmail);

		boolean isAdmin = currentUser.getRole() != null
				&& List.of("SUPERADMIN", "ADMIN").contains(currentUser.getRole().getRoleName().toUpperCase());

		User userToUpdate = (isAdmin && request.getId() != null)
				? userRepository.findById(request.getId()).orElseThrow(() -> new RuntimeException("User not found"))
				: currentUser;

		userToUpdate.setFullName(request.getFullName());
		userToUpdate.setPrimaryEmail(request.getPrimaryEmail());
		userToUpdate.setAlternativeEmail(request.getAlternativeEmail());
		userToUpdate.setMobileNumber(request.getMobileNumber());
		userToUpdate.setAlternativeMobileNumber(request.getAlternativeMobileNumber());
		userToUpdate.setTaxId(request.getTaxId());
		userToUpdate.setBusinessId(request.getBusinessId());
		userToUpdate.setPreferredCurrency(request.getPreferredCurrency());
		userToUpdate.setInvoicePrefix(request.getInvoicePrefix());
		userToUpdate.setCompanyName(request.getCompanyName());

		if (profileImage != null && !profileImage.isEmpty()) {
			try {
				String savedFileName = uploadFile(profileImage, userToUpdate.getId());
				userToUpdate.setProfilePicPath(savedFileName);
			} catch (Exception e) {
				throw new RuntimeException("Error saving profile image");
			}
		}

		return userRepository.save(userToUpdate);
	}

	/** ================= UPLOAD FILE ================= **/
	@Override
	public String uploadFile(MultipartFile file, Long userId) throws IOException {
		Files.createDirectories(Paths.get(uploadDir));
		String fileName = "user_" + userId + "_" + System.currentTimeMillis() + "_" + file.getOriginalFilename();
		Path filePath = Paths.get(uploadDir, fileName);
		Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
		return fileName;
	}

	/** ================= MAP USER TO DTO ================= **/

	@Override
	public UserUpdateRequest mapToDto(User user) {

		return UserUpdateRequest.builder().id(user.getId()).fullName(user.getFullName())
				.primaryEmail(user.getPrimaryEmail()).alternativeEmail(user.getAlternativeEmail())
				.mobileNumber(user.getMobileNumber()).alternativeMobileNumber(user.getAlternativeMobileNumber())
				.taxId(user.getTaxId()).businessId(user.getBusinessId()).preferredCurrency(user.getPreferredCurrency())
				.invoicePrefix(user.getInvoicePrefix()).companyName(user.getCompanyName()).state(user.getState())
				.country(user.getCountry()).city(user.getCity()).pincode(user.getPincode())
				.telephone(user.getTelephone()).ein(user.getEin()).gstin(user.getGstin()).website(user.getWebsite())
				.address(user.getAddress()).fid(user.getFid()).everifyId(user.getEverifyId())
				.dunsNumber(user.getDunsNumber()).stateOfIncorporation(user.getStateOfIncorporation())
				.naicsCode(user.getNaicsCode()).signingAuthorityName(user.getSigningAuthorityName())
				.designation(user.getDesignation()).dateOfIncorporation(user.getDateOfIncorporation())
				.bankDetails(user.getBankDetails()) // Make sure
				.build();
	}

	@Override
	public ManageUserDTO getByIdAndLoggedInUser(Long id, String loggedInEmail) {
		User currentUser = getCurrentLoggedInUser(loggedInEmail);
		ManageUsers targetUser = manageUserRepository.findById(id)
				.orElseThrow(() -> new com.invoice.exception.ResourceNotFoundException("User not found with ID: " + id));

		assertSameTenant(currentUser, targetUser, id);

		String role = currentUser.getRole().getRoleName();
		if ("SUPERADMIN".equalsIgnoreCase(role)) {
			return convertToDTO(targetUser);
		} else if ("ADMIN".equalsIgnoreCase(role)) {
			if ("SUPERADMIN".equalsIgnoreCase(targetUser.getRoleName())) {
				throw new RuntimeException("ADMIN cannot view SUPERADMIN data");
			}
			return convertToDTO(targetUser);
		} else if (targetUser.getEmail().equalsIgnoreCase(loggedInEmail)) {
			return convertToDTO(targetUser);
		} else {
			throw new RuntimeException("You can only view your own data");
		}
	}

	@Override
	@Transactional
	public User updateUserProfileDynamic(UserUpdateRequest request) {

		// ---------- UPDATE USER TABLE ----------

		User user = userRepository.findById(request.getId())
				.orElseThrow(() -> new RuntimeException("User not found with id: " + request.getId()));

		// Get email from the existing user entity
		String userEmail = user.getEmail();

		if (request.getFullName() != null)
			user.setFullName(request.getFullName());

		if (request.getEmail() != null)
			user.setEmail(request.getEmail());

		if (request.getMobileNumber() != null)
			user.setMobileNumber(request.getMobileNumber());

		if (request.getInvoicePrefix() != null)
			user.setInvoicePrefix(request.getInvoicePrefix());

		if (request.getCompanyName() != null)
			user.setCompanyName(request.getCompanyName());

		if (request.getAddress() != null)
			user.setAddress(request.getAddress());

		if (request.getState() != null)
			user.setState(request.getState());

		if (request.getCountry() != null)
			user.setCountry(request.getCountry());

		if (request.getCity() != null)
			user.setCity(request.getCity());

		if (request.getPincode() != null)
			user.setPincode(request.getPincode());

		if (request.getPreferredCurrency() != null)
			user.setPreferredCurrency(request.getPreferredCurrency());

		if (request.getTaxId() != null)
			user.setTaxId(request.getTaxId());

		if (request.getSigningAuthorityName() != null)
			user.setSigningAuthorityName(request.getSigningAuthorityName());

		if (request.getBusinessId() != null)
			user.setBusinessId(request.getBusinessId());

		if (request.getTelephone() != null)
			user.setTelephone(request.getTelephone());

		if (request.getEin() != null)
			user.setEin(request.getEin());

		if (request.getGstin() != null)
			user.setGstin(request.getGstin());

		if (request.getWebsite() != null)
			user.setWebsite(request.getWebsite());

		if (request.getFid() != null)
			user.setFid(request.getFid());

		if (request.getEverifyId() != null)
			user.setEverifyId(request.getEverifyId());

		if (request.getDunsNumber() != null)
			user.setDunsNumber(request.getDunsNumber());

		if (request.getSuite() != null)
			user.setSuite(request.getSuite());

		if (request.getBusinessCountry() != null)
			user.setBusinessCountry(request.getBusinessCountry());

		if (request.getCompanylogo() != null)
			user.setCompanylogo(request.getCompanylogo());

		if (request.getStateOfIncorporation() != null)
			user.setStateOfIncorporation(request.getStateOfIncorporation());

		if (request.getNaicsCode() != null)
			user.setNaicsCode(request.getNaicsCode());

		if (request.getDesignation() != null)
			user.setDesignation(request.getDesignation());

		if (request.getDateOfIncorporation() != null)
			user.setDateOfIncorporation(request.getDateOfIncorporation());

		if (request.getBankDetails() != null) {

			userRepository.save(user);

			// ---------- UPDATE MANAGE_USERS TABLE ----------

			// Use email from User entity instead of request
			ManageUsers manageUser = manageUserRepository.findByEmail(userEmail);

			if (manageUser == null) {
				throw new RuntimeException("Manage user not found with email: " + userEmail);
			}

			if (request.getFullName() != null)
				manageUser.setFullName(request.getFullName());

			if (request.getPrimaryEmail() != null)
				manageUser.setPrimaryEmail(request.getPrimaryEmail());

			if (request.getMobileNumber() != null)
				manageUser.setMobileNumber(request.getMobileNumber());

			if (request.getCompanyName() != null)
				manageUser.setCompanyName(request.getCompanyName());

			if (request.getAddress() != null)
				manageUser.setAddress(request.getAddress());

			if (request.getState() != null)
				manageUser.setState(request.getState());

			if (request.getCity() != null)
				manageUser.setCity(request.getCity());

			if (request.getCountry() != null)
				manageUser.setCountry(request.getCountry());

			if (request.getInvoicePrefix() != null)
				manageUser.setInvoicePrefix(request.getInvoicePrefix());

			if (request.getTaxId() != null)
				manageUser.setTaxId(request.getTaxId());

			if (request.getPincode() != null)
				manageUser.setPincode(request.getPincode());

			if (request.getTelephone() != null)
				manageUser.setTelephone(request.getTelephone());

			if (request.getEin() != null)
				manageUser.setEin(request.getEin());

			if (request.getGstin() != null)
				manageUser.setGstin(request.getGstin());

			if (request.getWebsite() != null)
				manageUser.setWebsite(request.getWebsite());

			if (request.getSuite() != null)
				manageUser.setSuite(request.getSuite());

			if (request.getBusinessCountry() != null)
				manageUser.setBusinessCountry(request.getBusinessCountry());

			if (request.getCompanylogo() != null)
				manageUser.setCompanylogo(request.getCompanylogo());

			if (request.getFid() != null)
				manageUser.setFid(request.getFid());

			if (request.getEverifyId() != null)
				manageUser.setEverifyId(request.getEverifyId());

			if (request.getDunsNumber() != null)
				manageUser.setDunsNumber(request.getDunsNumber());

			if (request.getStateOfIncorporation() != null)
				manageUser.setStateOfIncorporation(request.getStateOfIncorporation());

			if (request.getSigningAuthorityName() != null)
				manageUser.setSigningAuthorityName(request.getSigningAuthorityName());

			if (request.getDesignation() != null)
				manageUser.setDesignation(request.getDesignation());

			if (request.getDateOfIncorporation() != null)
				manageUser.setDateOfIncorporation(request.getDateOfIncorporation());

			if (request.getBankDetails() != null) {

				List<BankDetails> bankEntities = new ArrayList<>();

				for (BankDetails dto : request.getBankDetails()) {

					BankDetails bank = new BankDetails();
					bank.setId(dto.getId());
					bank.setBankName(dto.getBankName());
					bank.setBankAccountNumber(dto.getBankAccountNumber());
					bank.setRoutingNumber(dto.getRoutingNumber());

					bank.setUser(user); // VERY IMPORTANT

					bankEntities.add(bank);
				}

				user.getBankDetails().clear();
				user.getBankDetails().addAll(bankEntities);
			}

		}
		return user;
	}

	@Override
	public Page<ManageUserDTO> getAllManageUsersWithSorting(SortingRequestDTO sortingRequestDTO, String loggedInEmail) {

		String sortField = sortingRequestDTO.getSortField();
		String sortOrder = sortingRequestDTO.getSortOrder();
		String keyword = sortingRequestDTO.getKeyword();
		Integer pageNo = sortingRequestDTO.getPageNumber();
		Integer pageSize = sortingRequestDTO.getPageSize();

		// ✅ Get current user and their role
		User currentUser = getCurrentLoggedInUser(loggedInEmail);
		String roleName = currentUser.getRole() != null ? currentUser.getRole().getRoleName() : null;
		String domain = extractDomain(currentUser.getEmail());

		// ✅ Default values and validation
		if (pageNo == null || pageNo < 0) {
			pageNo = 0; // Default to first page
		}
		// ✅ If pageNo is 1 or greater, subtract 1 (convert to 0-based index)
		// If pageNo is already 0, keep it as 0
		int zeroBasedPageNo = (pageNo > 0) ? pageNo - 1 : pageNo;

		if (pageSize == null || pageSize < 1) {
			pageSize = 10; // Default page size
		}
		if (sortField == null || sortField.isEmpty()) {
			sortField = "id";
		}

		// ✅ Map frontend field names to entity field names
		switch (sortField.toLowerCase()) {
		case "name":
		case "fullname":
			sortField = "firstName";
			break;
		case "email":
			sortField = "email";
			break;
		case "role":
			sortField = "roleName";
			break;
		case "addedby":
			sortField = "addedByName";
			break;
		case "updatedby":
			sortField = "updated_by_name";
			break;
		case "companyname":
			sortField = "companyName";
			break;
		case "mobilenumber":
			sortField = "mobileNumber";
			break;
		default:
			sortField = "id";
			break;
		}

		// ✅ Determine sort direction
		Sort.Direction sortDirection = Sort.Direction.ASC;
		if (sortOrder != null && sortOrder.equalsIgnoreCase("desc")) {
			sortDirection = Sort.Direction.DESC;
		}

		// ✅ Create sort and pageable (using 0-based index)
		Sort sort = Sort.by(sortDirection, sortField);
		Pageable pageable = PageRequest.of(zeroBasedPageNo, pageSize, sort);

		Page<ManageUsers> manageUsersPage;

		// ✅ Check if keyword is provided
		boolean hasKeyword = keyword != null && !keyword.trim().isEmpty() && !keyword.equalsIgnoreCase("empty");

		// ✅ Filter based on role
		// ✅ Filter based on role
		if ("SUPERADMIN".equalsIgnoreCase(roleName)) {
			// SUPERADMIN sees ALL users
			if (hasKeyword) {
				manageUsersPage = manageUserRepository.searchManageUsers(keyword.trim(), pageable);
			} else {
				manageUsersPage = manageUserRepository.findAll(pageable);
			}

		} else if ("ADMIN".equalsIgnoreCase(roleName)) {
			// ADMIN sees ONLY their domain users
			if (hasKeyword) {
				manageUsersPage = manageUserRepository.searchManageUsersByDomain(keyword.trim(), domain, pageable);
			} else {
				manageUsersPage = manageUserRepository.getAllManageUsersByDomain(domain, pageable);
			}

		} else {
			// ✅ All other roles (HR, ACCOUNTANT etc.) see their own company's users
			if (hasKeyword) {
				manageUsersPage = manageUserRepository.searchManageUsersByDomain(keyword.trim(), domain, pageable);
			} else {
				manageUsersPage = manageUserRepository.getAllManageUsersByDomain(domain, pageable);
			}
		}

		// ✅ Convert to DTO
		return manageUsersPage.map(this::convertToDTO);
	}

	@Override
	public Optional<ManageUsers> findByAdminId(Long adminId) {
		throw new UnsupportedOperationException("Not implemented");
	}


	/**
	 * Refuses a record outside the caller's tenant.
	 *
	 * <p>The list endpoints in this service have always scoped by
	 * {@code companyDomain} — {@code findByCompanyDomainIgnoreCase(domain)}, with
	 * the domain derived server-side from the authenticated user's email. The
	 * by-id endpoints did not. They checked <em>role</em> only, so an ADMIN in one
	 * tenant could read, update and delete records belonging to another. Verified
	 * live: a tenant-1001 ADMIN retrieved a tenant-900 user with HTTP 200.
	 *
	 * <p>That asymmetry is the whole defect. A collection query that filters and a
	 * single-record lookup that does not is the most common shape of a
	 * cross-tenant IDOR, because the list screen looks correct in testing while
	 * the detail URL is what an attacker actually edits.
	 *
	 * <p>SUPERADMIN stays exempt: it is a platform-operator role, and the list
	 * path already treats it that way. Every other role is confined to its own
	 * domain.
	 *
	 * <p>Throws the same message as a missing record on purpose — telling a caller
	 * that an id exists in a tenant they cannot see is itself a disclosure, and
	 * lets them enumerate the platform's user ids.
	 */
	private void assertSameTenant(User currentUser, ManageUsers target, Long requestedId) {
		String role = currentUser.getRole() != null ? currentUser.getRole().getRoleName() : null;
		if ("SUPERADMIN".equalsIgnoreCase(role)) {
			return;
		}

		String callerDomain = extractDomain(currentUser.getEmail());
		String targetDomain = (target.getCompanyDomain() != null && !target.getCompanyDomain().isBlank())
				? target.getCompanyDomain()
				: extractDomain(target.getEmail());

		if (callerDomain == null || targetDomain == null || !callerDomain.equalsIgnoreCase(targetDomain)) {
			log.warn("Cross-tenant ManageUsers access refused: caller={} callerDomain={} "
					+ "targetId={} targetDomain={}",
					currentUser.getEmail(), callerDomain, requestedId, targetDomain);
			throw new com.invoice.exception.ResourceNotFoundException("User not found with ID: " + requestedId);
		}
	}

}