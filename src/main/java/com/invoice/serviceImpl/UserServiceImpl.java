package com.invoice.serviceImpl;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.HtmlUtils;

import com.invoice.DTO.LoginRequest;
import com.invoice.DTO.ManageUserDTO;
import com.invoice.DTO.RegisterRequest;
import com.invoice.DTO.UserProfileResponse;
import com.invoice.DTO.ManageUserDTO.ManageUserDTOBuilder;
import com.invoice.config.MailConfig;
import com.invoice.otp.OtpHasher;
import com.invoice.otp.OtpPurpose;
import com.invoice.otp.OtpRequestContext;
import com.invoice.otp.OtpService;
import com.invoice.otp.OtpVerificationResult;
import com.invoice.entity.ManageUsers;
import com.invoice.entity.Privilege;
import com.invoice.entity.Role;
import com.invoice.entity.User;
import com.invoice.exception.BusinessException;
import com.invoice.repository.AdminRepository;
import com.invoice.repository.ManageUserRepository;
import com.invoice.repository.PrivilegeRepository;
import com.invoice.repository.RoleRepository;
import com.invoice.repository.UserRepository;
import com.invoice.service.UserService;

import jakarta.mail.internet.MimeMessage;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class UserServiceImpl implements UserService {


	private final AdminRepository adminRepository;

	private final MailConfig mailConfig;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private JwtServiceImpl jwtServiceImpl;

	@Autowired
	private EntityManager entityManager;
	
	@Autowired
	private RoleRepository roleRepository;

	@Autowired
	private PrivilegeRepository privilegeRepository;

	@Autowired
	private ManageUserRepository manageUserRepository;

	@Autowired
	private JavaMailSender javaMailSender;

	@Autowired
	private OtpService otpService;

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

	/**
	 * Whether an account exists, without going through JPA.
	 *
	 * <p>Only for the OTP send path. Spring Boot leaves {@code open-in-view}
	 * enabled, so the first JPA call in a request binds an EntityManager that
	 * keeps its pooled connection until the response is rendered. On this path
	 * the response is not rendered until SMTP has answered, so one
	 * {@code userRepository} call was enough to hold a database connection for
	 * the entire mail exchange — and fifteen concurrent sends against a stalled
	 * relay leased the whole pool. A plain JDBC query borrows and returns
	 * immediately.
	 *
	 * <p>Everything off the send path keeps using {@code userRepository}; there
	 * is no reason to spread this around.
	 */
	private boolean accountExistsWithoutJpa(String normalisedEmail) {
		Integer found = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM user_info WHERE lower(email) = ?",
				Integer.class, normalisedEmail);
		return found != null && found > 0;
	}

	@Value("${spring.mail.username}")
	private String fromEmail;
	
	UserServiceImpl(MailConfig mailConfig, AdminRepository adminRepository, EntityManager entityManager) {
	    this.mailConfig = mailConfig;
	    this.adminRepository = adminRepository;
	    this.entityManager = entityManager;
	}
	
	private ManageUserDTO convertToDTO(ManageUsers user) {
		return ManageUserDTO.builder().id(user.getId()).fullName(user.getFullName()).firstName(user.getFirstName())
				.middleName(user.getMiddleName()).lastName(user.getLastName()).email(user.getEmail())
				.primaryEmail(user.getPrimaryEmail()).mobileNumber(user.getMobileNumber())
				.companyName(user.getCompanyName()).roleName(user.getRoleName())
				.addedBy(user.getAddedBy() != null ? user.getAddedBy().getId().toString() : null)
				.addedByName(user.getAddedByName()).updatedByName(user.getUpdatedByName())
				.businessCountry(user.getBusinessCountry()).state(user.getState()).country(user.getCountry())
				.pincode(user.getPincode()).city(user.getCity()).suite(user.getSuite())
				.companylogo(user.getCompanylogo()).companyDomain(user.getCompanyDomain())
				.telephone(user.getTelephone()).ein(user.getEin()).gstin(user.getGstin()).website(user.getWebsite())
				.address(user.getAddress()).loginUrl(user.getLoginUrl()).adminId(user.getAdminId()).build();
	}
	//

	private String extractDomain(String email) {
		if (email == null || !email.contains("@")) {
			return null;
		}

		return email.substring(email.indexOf("@") + 1).toLowerCase();
	}

	public boolean isEmailDuplicate(String email) {
		return userRepository.existsByEmail(email);
	}

	@Transactional
	public ManageUserDTO registerCompanyUser(ManageUsers manageUsers) {

	    final String ADMIN_ROLE = "ADMIN";

	    String mobileNumber = manageUsers.getMobileNumber();
	    String companyName = manageUsers.getCompanyName();
	    String state = manageUsers.getState();
	    String country = manageUsers.getCountry();
	    String city = manageUsers.getCity();
	    String pincode = manageUsers.getPincode();
	    String telephone = manageUsers.getTelephone();
	    String ein = manageUsers.getEin();
	    String gstin = manageUsers.getGstin();
	    String website = manageUsers.getWebsite();
	    String address = manageUsers.getAddress();
	    String businessCountry = manageUsers.getBusinessCountry();
	    String companylogo = manageUsers.getCompanylogo();
	    String suite = manageUsers.getSuite();

	    if (manageUsers.getEmail() == null || manageUsers.getEmail().isBlank()) {
	        throw new BusinessException("Email is required");
	    }

	    String email = manageUsers.getEmail().trim().toLowerCase();
	    manageUsers.setEmail(email);
	    manageUsers.setPrimaryEmail(email);

	    String domain = extractDomain(email);
	    String domainLower = domain == null ? null : domain.toLowerCase();
	    java.util.Set<String> freeEmailDomains = java.util.Set.of(
	        "gmail.com", "yahoo.com", "outlook.com", "hotmail.com",
	        "live.com", "icloud.com", "aol.com", "protonmail.com");
	    if (domainLower != null && freeEmailDomains.contains(domainLower)) {
	        throw new RuntimeException(
	            "Free-email domains are not allowed as company tenant keys. " +
	            "Register with a company email (e.g. you@yourcompany.com) instead of " + domainLower + ".");
	    }
	    manageUsers.setCompanyDomain(domain);

	    if (manageUserRepository.existsByCompanyDomainAndRole_RoleNameIgnoreCase(domain, ADMIN_ROLE)) {
	        throw new BusinessException("Company already registered. Please contact your company administrator.");
	    }

	    User user = userRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
	        User u = new User();
	        u.setEmail(email);
	        u.setFirstName(manageUsers.getFirstName());
	        u.setCompanyName(companyName);
	        u.setMobileNumber(mobileNumber);
	        u.setState(state);
	        u.setCountry(country);
	        u.setCity(city);
	        u.setPincode(pincode);
	        u.setTelephone(telephone);
	        u.setEin(ein);
	        u.setGstin(gstin);
	        u.setWebsite(website);
	        u.setAddress(address);
	        u.setCompanylogo(companylogo);
	        u.setCompanyDomain(domain);
	        u.setSuite(suite);
	        u.setBusinessCountry(businessCountry);
	        u.setApproved(true);
	        u.setActive(true);
	        return userRepository.save(u);
	    });

	    // ✅ Single Role creation — duplicate block removed
	    Role adminRole = new Role();
	    adminRole.setRoleName(ADMIN_ROLE);
	    adminRole.setAdminId(user.getId());
	    adminRole.setDescription("Administrator role with full access for company: " + domain);

	 // In registerCompanyUser() — no change needed here, same line works
	    List<Privilege> fetchedPrivileges = privilegeRepository.findAllPrivilegesFresh();    
	    adminRole.setPrivileges(new HashSet<>(fetchedPrivileges));
	    Role savedAdminRole = roleRepository.save(adminRole);

	    user.setRole(savedAdminRole);
	    userRepository.save(user);

	    manageUsers.setAdminId(user.getId());
	    manageUsers.setRole(savedAdminRole);
	    manageUsers.setRoleName(savedAdminRole.getRoleName());

	    manageUsers.setMobileNumber(mobileNumber);
	    manageUsers.setCompanyName(companyName);
	    manageUsers.setState(state);
	    manageUsers.setCountry(country);
	    manageUsers.setCity(city);
	    manageUsers.setPincode(pincode);
	    manageUsers.setTelephone(telephone);
	    manageUsers.setEin(ein);
	    manageUsers.setGstin(gstin);
	    manageUsers.setWebsite(website);
	    manageUsers.setAddress(address);
	    manageUsers.setCompanylogo(companylogo);
	    manageUsers.setCompanyDomain(domain);
	    manageUsers.setSuite(suite);
	    manageUsers.setBusinessCountry(businessCountry);

	    manageUsers.setApproved(true);
	    manageUsers.setActive(true);
	    manageUsers.setAddedByName("SELF-REGISTERED");
	    manageUsers.setCreatedBy(user);
	    manageUsers.setAddedBy(user);

	    ManageUsers saved = manageUserRepository.save(manageUsers);

	    return convertToDTO(saved);
	}
	/** ===================== Register new user ===================== **/
	@Override
	public String register(User user) {
		if (userRepository.findByEmailIgnoreCase(user.getEmail()).isPresent()) {
			throw new RuntimeException("Email is already registered!");
		}

		boolean isFirstUser = userRepository.count() == 0;

		if (isFirstUser) {
			Role superAdminRole = roleRepository.findByRoleName("ADMIN")
					.orElseThrow(() -> new RuntimeException("ADMIN role not found in DB!"));
			user.setRole(superAdminRole);
			user.setApproved(true);
			user.setActive(true);
		} else {
			user.setRole(null);
			user.setApproved(false);
			user.setActive(false);
		}

		userRepository.save(user);
		return "User registered successfully!";
	}

	/**
	 * Sends a sign-in passcode.
	 *
	 * <p>Generation, hashing, storage, rate limiting, delivery and audit all
	 * live in {@link OtpService} now. What was here before drew from
	 * {@code java.util.Random}, stored the passcode in plaintext, hardcoded a
	 * two-minute expiry, and composed an HTML-only mail inline at three
	 * separate call sites.
	 *
	 * <p>The account lookup is passed as a predicate rather than performed
	 * here, so that {@code OtpService} controls the order of work and an
	 * unknown address takes the same path as a known one.
	 *
	 * <p><strong>Not {@code @Transactional}, deliberately.</strong> It used to be,
	 * and the annotation survived the OTP rewrite by inertia. It undid the whole
	 * point of that rewrite: {@link OtpService#request} carefully runs two short
	 * transactions with the SMTP call outside both, but an outer transaction
	 * started here simply wraps all of it, so the connection is held across the
	 * mail exchange again. Measured against a stalled relay on the running
	 * stack: fifteen concurrent requests left all ten pool connections
	 * {@code idle in transaction} for the full SMTP timeout. Nothing in this
	 * method needs a transaction of its own — the only database work is inside
	 * OtpService, which manages its own boundaries.
	 */
	@Override
	public void sendOtp(String emailInput, OtpRequestContext context) {
		otpService.request(emailInput, OtpPurpose.LOGIN,
				// Deliberately JDBC, not userRepository: any JPA call here would bind
				// an EntityManager for the request under open-in-view and hold a
				// pooled connection across the SMTP exchange that follows.
				this::accountExistsWithoutJpa,
				context);
	}

	/**
	 * Sends a registration passcode.
	 *
	 * <p>Registration is the one flow that deliberately reports a collision:
	 * someone who already has an account has to be told to sign in instead, and
	 * {@code GET /auth/check-email/{email}} already answers the same question
	 * without authentication. That disclosure is documented in
	 * docs/INVOICE_OTP_SECURITY.md rather than being an accident of this method.
	 *
	 * <p><strong>Not {@code @Transactional}, deliberately.</strong> It used to be,
	 * and the annotation survived the OTP rewrite by inertia. It undid the whole
	 * point of that rewrite: {@link OtpService#request} carefully runs two short
	 * transactions with the SMTP call outside both, but an outer transaction
	 * started here simply wraps all of it, so the connection is held across the
	 * mail exchange again. Measured against a stalled relay on the running
	 * stack: fifteen concurrent requests left all ten pool connections
	 * {@code idle in transaction} for the full SMTP timeout. Nothing in this
	 * method needs a transaction of its own — the only database work is inside
	 * OtpService, which manages its own boundaries.
	 */
	@Override
	public void sendOtpForRegister(String emailInput, OtpRequestContext context) {
		final String email = OtpHasher.normaliseIdentifier(emailInput);

		// JDBC for the same reason as accountExistsWithoutJpa: this runs on the
		// send path, ahead of an SMTP call that may stall.
		if (accountExistsWithoutJpa(email)) {
			throw new RuntimeException("Email already registered. Please login.");
		}

		otpService.request(email, OtpPurpose.REGISTRATION,
				identifier -> false, context);
	}

	/** ===================== Login with OTP ===================== **/
	@Override
	@Transactional
	public Map<String, Object> loginWithOtp(LoginRequest request, OtpRequestContext context) {
	    String email = request.getEmail().trim().toLowerCase();
	    String enteredOtp = request.getOtp();

	    // Verify the passcode first, and only then resolve the account.
	    //
	    // The order is the point. This method used to look up ManageUsers first
	    // and throw "Invalid credentials: email not registered", so an
	    // unauthenticated caller could tell a registered address from an
	    // unregistered one without holding a code at all. Verification now
	    // happens against the identifier alone, and every failure — unknown
	    // address, wrong code, expired, replayed — leaves by the same branch
	    // with the same message.
	    OtpVerificationResult verification =
	            otpService.verify(email, OtpPurpose.LOGIN, enteredOtp, context);
	    if (!verification.isVerified()) {
	        throw new RuntimeException(OtpVerificationResult.userFacingFailureMessage());
	    }

	    // Fetch ManageUsers. Reaching here means a code that was genuinely
	    // issued to this address was presented, so an absent account is a
	    // provisioning fault rather than a probe.
	    ManageUsers manageUser = manageUserRepository.findByEmailIgnoreCase(email)
	            .orElseThrow(() -> new RuntimeException("Invalid credentials: email not registered"));

	    // Fetch linked User
	    User user = userRepository.findByEmailIgnoreCase(email)
	            .orElseThrow(() -> new RuntimeException("User not found in system"));

	    if (!user.getActive()) {
	        throw new RuntimeException("User is inactive. Contact admin.");
	    }

	    Long roleId = manageUser.getRole().getRoleId();
	    String roleName = null;
	    Set<String> privilegeNames = new HashSet<>();

	    // ✅ FIX 1: fetch privileges directly via PrivilegeRepository
	    // to avoid Hibernate lazy loading issue
	    if (roleId != null) {
	        Role roleEntity = roleRepository.findById(roleId).orElse(null);

	        if (roleEntity != null) {
	            roleName = roleEntity.getRoleName();

	            privilegeNames = privilegeRepository.findByRoles_RoleId(roleId)
	                    .stream()
	                    .map(Privilege::getName)
	                    .collect(Collectors.toSet());
	        }
	    }

	    // Tenant boundary: adminId from the ManageUsers record (set at registration to the
	    // owning admin's user_info.id). Required for every downstream service to scope data.
	    Long tenantAdminId = manageUser.getAdminId() != null ? manageUser.getAdminId() : user.getId();

	    // Generate JWT with adminId + roleName + privileges
	    String jwtToken = jwtServiceImpl.generateToken(user, tenantAdminId, roleName, privilegeNames);

	    // Prepare response data
	    Map<String, Object> data = new HashMap<>();
	    data.put("token", jwtToken);
	    data.put("userId", manageUser.getId());
	    data.put("email", user.getEmail());
	    data.put("firstName", user.getFirstName());
	    data.put("middleName", user.getMiddleName());
	    data.put("lastName", user.getLastName());
	    data.put("userRole", roleName);
	    data.put("rolePrivileges", privilegeNames);

	    // Admin info
	    User admin = manageUser.getAddedBy();
	    data.put("adminId", admin != null ? admin.getId() : null);
	    data.put("adminName", admin != null ? admin.getFullName() : "SYSTEM");
	    data.put("adminEmail", admin != null ? admin.getEmail() : null);

	    // Creator info
	    User creator = manageUser.getCreatedBy();
	    if (creator != null) {
	        data.put("createdById", creator.getId());
	        data.put("createdByName", creator.getFullName());
	        data.put("createdByEmail", creator.getEmail());
	    }

	    // ✅ FIX 2: replaced Map.of(...) with HashMap to avoid double-wrap
	    Map<String, Object> response = new HashMap<>();
	    response.put("status", "success");
	    response.put("message", "User logged in successfully");
	    response.put("data", data);
	    response.put("pagesize", 0);
	    response.put("timeStamp", LocalDate.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy")));

	    return response;
	}
	/** ===================== Other Methods ===================== **/
	@Override
	public Optional<User> getUserById(Long id) {
		return userRepository.findById(id);
	}

	@Override
	public User updateUserProfile(Long id, User updatedProfile) {
		return userRepository.findById(id).map(existingUser -> {
			if (updatedProfile.getFullName() != null)
				existingUser.setFullName(updatedProfile.getFullName());
			if (updatedProfile.getPrimaryEmail() != null)
				existingUser.setPrimaryEmail(updatedProfile.getPrimaryEmail());
			if (updatedProfile.getAlternativeEmail() != null)
				existingUser.setAlternativeEmail(updatedProfile.getAlternativeEmail());
			if (updatedProfile.getMobileNumber() != null)
				existingUser.setMobileNumber(updatedProfile.getMobileNumber());
			if (updatedProfile.getAlternativeMobileNumber() != null)
				existingUser.setAlternativeMobileNumber(updatedProfile.getAlternativeMobileNumber());
			if (updatedProfile.getCompanyName() != null)
				existingUser.setCompanyName(updatedProfile.getCompanyName());
			if (updatedProfile.getTaxId() != null)
				existingUser.setTaxId(updatedProfile.getTaxId());
			if (updatedProfile.getBusinessId() != null)
				existingUser.setBusinessId(updatedProfile.getBusinessId());
			if (updatedProfile.getPreferredCurrency() != null)
				existingUser.setPreferredCurrency(updatedProfile.getPreferredCurrency());
			if (updatedProfile.getInvoicePrefix() != null)
				existingUser.setInvoicePrefix(updatedProfile.getInvoicePrefix());
			return userRepository.save(existingUser);
		}).orElseThrow(() -> new RuntimeException("User not found with id " + id));
	}

	/**
	 * Loads a user for the profile endpoints, which serialise the entity.
	 *
	 * <p>Uses the fetch graph rather than the plain finder because Jackson
	 * serialises this after the transaction has closed — see
	 * {@code UserRepository.findWithProfileByEmailIgnoreCase}.
	 */
	/**
	 * Whether an address belongs to the given tenant.
	 *
	 * <p>The tenant boundary is {@code ManageUsers.adminId}, matching the claim
	 * the JWT carries. Answers false for an unknown address, so the caller
	 * cannot distinguish "not in your tenant" from "does not exist".
	 */
	@Override
	@org.springframework.transaction.annotation.Transactional(readOnly = true)
	public boolean isInTenant(String email, Long tenantAdminId) {
		if (email == null || email.isBlank() || tenantAdminId == null) {
			return false;
		}
		return manageUserRepository.findByEmailIgnoreCase(OtpHasher.normaliseIdentifier(email))
				.map(ManageUsers::getAdminId)
				.filter(tenantAdminId::equals)
				.isPresent();
	}

	@Override
	public Optional<User> getUserByEmail(String email) {
		return userRepository.findWithProfileByEmailIgnoreCase(email);
	}

	@Override
	public Map<String, Object> getPrivilegesForUser(Long userId) {
		User user = userRepository.findById(userId).orElseThrow(() -> new RuntimeException("User not found"));

		Role role = user.getRole();
		if (role == null)
			throw new RuntimeException("User has no role assigned");

		Set<Privilege> privileges;
		if ("ADMIN".equalsIgnoreCase(role.getRoleName())) {
			privileges = new HashSet<>(privilegeRepository.findAll());
		} else {
			privileges = role.getPrivileges();
		}

		Map<String, Object> result = new HashMap<>();
		result.put("role", role.getRoleName());
		result.put("privileges", privileges.stream()
				.map(p -> Map.of("id", p.getId(), "name", p.getName(), "cardType", p.getCardType(), "selected", true))
				.toList());
		return result;
	}

	private String getSafeFullName(User user) {
		String name = String.join(" ", Optional.ofNullable(user.getFirstName()).orElse(""),
				Optional.ofNullable(user.getMiddleName()).orElse(""),
				Optional.ofNullable(user.getLastName()).orElse("")).trim();
		return name.isEmpty() ? user.getEmail().split("@")[0] : name;
	}

	@Override
	@org.springframework.transaction.annotation.Transactional(readOnly = true)
	public UserProfileResponse getUserProfileByEmail(String email) {

		String normalizedEmail = email.trim().toLowerCase();

		// Fetch graph plus a read-only transaction: this method both reads lazy
		// associations while mapping and returns a response the serialiser walks.
		Optional<User> userOpt = userRepository.findWithProfileByEmailIgnoreCase(normalizedEmail);
		Optional<ManageUsers> muOpt = manageUserRepository.findByEmailIgnoreCase(normalizedEmail);

		if (userOpt.isEmpty() && muOpt.isEmpty()) {
			return null;
		}

		User user = userOpt.orElse(null);
		ManageUsers mu = muOpt.orElse(null);

		return UserProfileResponse.builder().id(user != null ? user.getId() : 0L).fullName(resolveFullName(user, mu))
				.primaryEmail(user != null && hasText(user.getPrimaryEmail()) ? user.getPrimaryEmail()
						: mu != null ? safe(mu.getEmail()) : normalizedEmail)

				.mobileNumber(user != null && hasText(user.getMobileNumber()) ? user.getMobileNumber()
						: mu != null ? safe(mu.getMobileNumber()) : "")

				.alternativeEmail(user != null && hasText(user.getAlternativeEmail()) ? user.getAlternativeEmail() : "")

				.alternativeMobileNumber(
						user != null && hasText(user.getAlternativeMobileNumber()) ? user.getAlternativeMobileNumber()
								: "")

				.companyName(user != null && hasText(user.getCompanyName()) ? user.getCompanyName()
						: mu != null ? safe(mu.getCompanyName()) : "")

				// ✅ Address & Company Details
				.state(user != null && hasText(user.getState()) ? user.getState() : "")
				.country(user != null && hasText(user.getCountry()) ? user.getCountry() : "")
				.city(user != null && hasText(user.getCity()) ? user.getCity() : "")
				.pincode(user != null && hasText(user.getPincode()) ? user.getPincode() : "")
				.telephone(user != null && hasText(user.getTelephone()) ? user.getTelephone() : "")
				.ein(user != null && hasText(user.getEin()) ? user.getEin() : "")
				.gstin(user != null && hasText(user.getGstin()) ? user.getGstin() : "")
				.website(user != null && hasText(user.getWebsite()) ? user.getWebsite() : "")
				.address(user != null && hasText(user.getAddress()) ? user.getAddress() : "")
				.businessCountry(user != null && hasText(user.getBusinessCountry()) ? user.getBusinessCountry() : "")
				.companylogo(user != null && hasText(user.getCompanylogo()) ? user.getCompanylogo() : "")
				.suite(user != null && hasText(user.getSuite()) ? user.getSuite() : "")
				// ✅ Newly Added Fields
				.fid(user != null && hasText(user.getFid()) ? user.getFid() : "")
				.everifyId(user != null && hasText(user.getEverifyId()) ? user.getEverifyId() : "")
				.dunsNumber(user != null && hasText(user.getDunsNumber()) ? user.getDunsNumber() : "")
				.stateOfIncorporation(
						user != null && hasText(user.getStateOfIncorporation()) ? user.getStateOfIncorporation() : "")
				.naicsCode(user != null && hasText(user.getNaicsCode()) ? user.getNaicsCode() : "")
				.signingAuthorityName(
						user != null && hasText(user.getSigningAuthorityName()) ? user.getSigningAuthorityName() : "")
				.designation(user != null && hasText(user.getDesignation()) ? user.getDesignation() : "")
				.dateOfIncorporation(
						user != null && hasText(user.getDateOfIncorporation()) ? user.getDateOfIncorporation() : "")

				// ✅ Bank Details (Safe Handling)
				.bankDetails(
						user != null && user.getBankDetails() != null ? user.getBankDetails() : Collections.emptyList())

				.taxId(user != null && hasText(user.getTaxId()) ? user.getTaxId() : "")
				.businessId(user != null && hasText(user.getBusinessId()) ? user.getBusinessId() : "")
				.preferredCurrency(
						user != null && hasText(user.getPreferredCurrency()) ? user.getPreferredCurrency() : "")
				.invoicePrefix(user != null && hasText(user.getInvoicePrefix()) ? user.getInvoicePrefix() : "")
				.profilePicPath(user != null && hasText(user.getProfilePicPath()) ? user.getProfilePicPath() : "")

				.role(mu != null && mu.getRole() != null ? mu.getRole().getRoleName()
						: user != null && user.getRole() != null ? user.getRole().getRoleName() : "")
				.build();

	}

	private String resolveFullName(User user, ManageUsers mu) {
		if (mu != null && hasText(mu.getFullName())) {
			return mu.getFullName();
		}
		if (user != null && hasText(user.getFullName())) {
			return user.getFullName();
		}
		return "";
	}

	private String safe(String value) {
		return value != null ? value : "";
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	/**
	 * Standalone verification for the sign-in screen's "check my code" step.
	 *
	 * <p>Returns a boolean and nothing else. The previous implementation threw
	 * three distinguishable messages from this unauthenticated endpoint — "OTP
	 * not found for this email", "OTP has expired" and "Invalid OTP" — which
	 * told a caller with no credentials whether an address had a code
	 * outstanding, and let them tell a stale code from a wrong one. All failures
	 * are now one answer; the precise reason goes to the audit log.
	 */
	@Override
	public boolean verifyOtp(String emailInput, String otpInput, OtpRequestContext context) {
		return otpService.verify(emailInput, OtpPurpose.LOGIN, otpInput, context).isVerified();
	}

	/**
	 * Verifies a registration passcode.
	 *
	 * <p>A separate method from {@link #verifyOtp} because the purposes are
	 * separate, and that separation is the point: a registration code must not
	 * satisfy a sign-in and a sign-in code must not complete a registration.
	 *
	 * <p>Both flows previously shared {@code POST /auth/login/verify-otp}, which
	 * worked only because the old table had no purpose column at all. Binding
	 * the login endpoint to LOGIN without giving registration its own endpoint
	 * would have left registration unable to verify anything.
	 */
	@Override
	public boolean verifyRegistrationOtp(String emailInput, String otpInput,
			OtpRequestContext context) {
		OtpVerificationResult result =
				otpService.verify(emailInput, OtpPurpose.REGISTRATION, otpInput, context);
		if (result.isVerified()) {
			otpService.recordFlowCompletion(OtpPurpose.REGISTRATION,
					OtpHasher.normaliseIdentifier(emailInput), null);
		}
		return result.isVerified();
	}

	public ManageUsers buildManageUsersFromRequest(RegisterRequest request) {

		ManageUsers manageUsers = new ManageUsers();

		manageUsers.setFirstName(request.getFirstName());
		manageUsers.setMiddleName(request.getMiddleName());
		manageUsers.setLastName(request.getLastName());

		// Build full name if required
		String fullName = request.getFirstName() + " "
				+ (request.getMiddleName() != null ? request.getMiddleName() + " " : "") + request.getLastName();

		manageUsers.setFullName(fullName.trim());

		manageUsers.setEmail(request.getEmail());
		manageUsers.setPrimaryEmail(request.getEmail());

		manageUsers.setMobileNumber(request.getMobileNumber());

		manageUsers.setCompanyName(request.getCompanyName());

		manageUsers.setBusinessCountry(request.getBusinessCountry());

		manageUsers.setState(request.getState());
		manageUsers.setCity(request.getCity());
		manageUsers.setCountry(request.getCountry());

		manageUsers.setPincode(request.getPincode());
		manageUsers.setTelephone(request.getTelephone());

		manageUsers.setEin(request.getEin());
		manageUsers.setGstin(request.getGstin());
		manageUsers.setAdminId(request.getAdminId());
		manageUsers.setWebsite(request.getWebsite());
		manageUsers.setAddress(request.getAddress());
		manageUsers.setBusinessCountry(request.getBusinessCountry());
		manageUsers.setCompanylogo(request.getCompanylogo());
		manageUsers.setCompanyDomain(request.getCompanyDomain());
		manageUsers.setSuite(request.getSuite());

		// Optional fields (if available in RegisterRequest)
//		    manageUsers.setAlternativeEmail(request.getAlternativeEmail());
//		    manageUsers.setAlternativeMobileNumber(request.getAlternativeMobileNumber());

		// Default flags
		manageUsers.setActive(true);
		manageUsers.setApproved(true);

		return manageUsers;
	}

	/**
	 * Re-authenticates a signed-in user before a bank-detail change.
	 *
	 * <p>Issued under its own purpose. Previously this wrote into the same
	 * single-row-per-email table that login read, so a code mailed out to
	 * confirm a bank change also satisfied a sign-in.
	 */
	@Override
	public void accountnumbersendOTP(String emailInput, OtpRequestContext context) {
		otpService.request(emailInput, OtpPurpose.ACCOUNT_NUMBER_CHANGE,
				this::accountExistsWithoutJpa, context);
	}

}