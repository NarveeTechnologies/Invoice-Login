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
import com.invoice.entity.ManageUsers;
import com.invoice.entity.OTP;
import com.invoice.entity.Privilege;
import com.invoice.entity.Role;
import com.invoice.entity.User;
import com.invoice.exception.BusinessException;
import com.invoice.repository.AdminRepository;
import com.invoice.repository.ManageUserRepository;
import com.invoice.repository.PrivilegeRepository;
import com.invoice.repository.RoleRepository;
import com.invoice.repository.TokenRepository;
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
	private TokenRepository tokenRepository;

	@Autowired
	private RoleRepository roleRepository;

	@Autowired
	private PrivilegeRepository privilegeRepository;

	@Autowired
	private ManageUserRepository manageUserRepository;

	@Autowired
	private JavaMailSender javaMailSender;

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
	 * Generate alphanumeric OTP
	 * 
	 * @param length - length of OTP (default: 6)
	 * @return alphanumeric OTP string
	 */
	private String generateAlphanumericOTP(int length) {
		if (length != 6) {
			throw new IllegalArgumentException("OTP length must be 6 for this pattern");
		}

		String alphabets = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
		String numbers = "0123456789";

		Random random = new Random();

		StringBuilder otp = new StringBuilder();

		// Generate 3 random alphabets
		for (int i = 0; i < 3; i++) {
			otp.append(alphabets.charAt(random.nextInt(alphabets.length())));
		}

		// Generate 3 random numbers
		for (int i = 0; i < 3; i++) {
			otp.append(numbers.charAt(random.nextInt(numbers.length())));
		}

		// Now shuffle them so pattern is mixed like A7K9M2
		List<Character> otpChars = new ArrayList<>();
		for (char c : otp.toString().toCharArray()) {
			otpChars.add(c);
		}

		Collections.shuffle(otpChars);

		StringBuilder finalOtp = new StringBuilder();
		for (char c : otpChars) {
			finalOtp.append(c);
		}

		return finalOtp.toString();
	}

	@Transactional
	@Override
	public void sendOtp(String emailInput) {
		final String email = emailInput.trim().toLowerCase();

		// Fetch user
		User user = userRepository.findByEmailIgnoreCase(email)
				.orElseThrow(() -> new RuntimeException("Invalid credentials: email not registered"));

		// Build full name
		String fullName = (user.getFullName() != null && !user.getFullName().isBlank()) ? user.getFullName()
				: (user.getFirstName() != null ? user.getFirstName() : email.split("@")[0]);
		String safeFullname = HtmlUtils.htmlEscape(fullName);

		// Remove old OTPs
		tokenRepository.deleteByEmail(email);

		// ✅ Generate new ALPHANUMERIC OTP
		String otp = generateAlphanumericOTP(6); // 6-character alphanumeric OTP
		long expiryTime = System.currentTimeMillis() + 2 * 60_000; // 2 minutes

		OTP otpEntity = new OTP(null, email, otp, expiryTime);
		tokenRepository.save(otpEntity);

		// Send email with designed HTML
		try {
			MimeMessage mimeMessage = javaMailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);
			helper.setFrom(fromEmail);
			helper.setTo(email);
			helper.setSubject("Login Verification Code - Invoicing Team");

			String htmlContent = "<!DOCTYPE html>" + "<html>" + "<head><meta charset='UTF-8'></head>"
					+ "<body style='margin:0; padding:0; font-family: Arial, sans-serif; background-color:#f9f9f9;'>"
					+ "<table align='center' width='600' cellpadding='0' cellspacing='0' style='background:#ffffff; border-radius:8px; box-shadow:0 4px 8px rgba(0,0,0,0.1);'>"
					+ "<tr>"
					+ "<td align='center' bgcolor='#2563eb' style='padding:20px; border-top-left-radius:8px; border-top-right-radius:8px;'>"
					+ "<h2 style='color:#ffffff; margin:0;'> Invoice </h2>" + "</td>" + "</tr>" + "<tr>"
					+ "<td style='padding:30px;'>" + "<h3 style='color:#004b6e; margin-top:0;'>Invoicing Team</h3>"
					+ "<p style='font-size:16px; color:#4b5563;'>" + "Hello <strong>" + safeFullname
					+ "</strong>,<br><br>"

					+ "Thank you for choosing <b>Invoicing Application</b>. Your verification code is:" + "</p>"
					+ "<div style='display:inline-block; text-align:center; padding:18px 20px; border-radius:12px; background:#eff6ff; font-size:30px; font-weight:700; letter-spacing:0px; color:#1e3a8a;'>"
					+ otp.trim() + "</div>" + "<p style='text-align:center; font-size:15px; color:#6b7280;'>"
					+ "This OTP is valid for <strong>2 minutes</strong>. Please do not share this code with anyone."
					+ "</p>" + "<p style='font-size:14px; color:#333; margin-top:30px;'>"
					+ "Best Regards,<br><b>Invoicing Team</b>" + "</p>" + "</td>" + "</tr>" + "<tr>"
					+ "<td align='center' bgcolor='#f1f1f1' style='padding:10px; border-bottom-left-radius:8px; border-bottom-right-radius:8px; font-size:12px; color:#888;'>"
					+ "2026 Invoicing Team. All rights reserved." + "</td>" + "</tr>" + "</table>" + "</body>"
					+ "</html>";

			helper.setText(htmlContent, true);
			javaMailSender.send(mimeMessage);
			log.info("OTP sent successfully to {}", email);
		} catch (Exception e) {
			log.error("Failed to send OTP email to {}: {}", email, e.getMessage(), e);
		}
	}

	@Transactional
	@Override
	public void sendOtpForRegister(String emailInput) {

		final String email = emailInput.trim().toLowerCase();

		// ❌ Block if email already exists
		if (userRepository.existsByEmailIgnoreCase(email)) {
			throw new RuntimeException("Email already registered. Please login.");
		}

		// Clean old OTPs
		tokenRepository.deleteByEmail(email);

		// ✅ Generate ALPHANUMERIC OTP
		String otp = generateAlphanumericOTP(6); // 6-character alphanumeric OTP
		long expiryTime = System.currentTimeMillis() + 2 * 60_000;

		OTP otpEntity = new OTP(null, email, otp, expiryTime);
		tokenRepository.save(otpEntity);

		// Send email (reuse SAME template)
		sendOtpEmail(email, email.split("@")[0], otp);

		log.info("Registration OTP sent to {}", email);
	}

	private void sendOtpEmail(String email, String fullName, String otp) {
		try {
			MimeMessage mimeMessage = javaMailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

			helper.setFrom(fromEmail);
			helper.setTo(email);
			helper.setSubject("Verification Code - Invoicing Team");

			String safeFullname = HtmlUtils.htmlEscape(fullName);

			String htmlContent = "<!DOCTYPE html>" + "<html>" + "<head><meta charset='UTF-8'></head>"
					+ "<body style='margin:0; padding:0; font-family: Arial, sans-serif; background-color:#f9f9f9;'>"
					+ "<table align='center' width='600' cellpadding='0' cellspacing='0' style='background:#ffffff; border-radius:8px; box-shadow:0 4px 8px rgba(0,0,0,0.1);'>"
					+ "<tr>"

					+ "<td align='center' bgcolor='#2563eb' style='padding:20px; border-top-left-radius:8px; border-top-right-radius:8px;'>"
					+ "<h2 style='color:#ffffff; margin:0;'>Verify Your Registration</h2>" + "</td>" + "</tr>" + "<tr>"
					+ "<td style='padding:30px;'>" + "<h3 style='color:#004b6e; margin-top:0;'>Invoicing Team</h3>"
					+ "<p style='font-size:16px; color:#4b5563;'>" + "Hello <strong>" + safeFullname
					+ "</strong>,<br><br>"

					+ "Thank you for choosing <b>Invoicing Application</b>. Use the following OTP to complete your Registration:"
					+ "</p>" + "<div style='text-align:center; margin:32px 0;'>"
					+ "<div style='display:inline-block; padding:18px 32px; border-radius:12px; border:2px dashed #2563eb; background:#eff6ff; font-size:36px; font-weight:700; letter-spacing:8px; color:#1e3a8a;'>"
					+ otp + "</div>" + "</div>" + "<p style='text-align:center; font-size:15px; color:#6b7280;'>"
					+ "This OTP is valid for <strong>2 minutes</strong>. Please do not share this code with anyone."

					+ "</p>" + "<p style='font-size:14px; color:#333; margin-top:30px;'>"
					+ "Best Regards,<br><b>Invoicing Team</b>" + "</p>" + "</td>" + "</tr>" + "<tr>"
					+ "<td align='center' bgcolor='#f1f1f1' style='padding:10px; border-bottom-left-radius:8px; border-bottom-right-radius:8px; font-size:12px; color:#888;'>"
					+ "2026 Invoicing Team. All rights reserved." + "</td>" + "</tr>" + "</table>" + "</body>"
					+ "</html>";

			helper.setText(htmlContent, true);
			javaMailSender.send(mimeMessage);

		} catch (Exception e) {
			log.error("Failed to send OTP email to {}", email, e);
		}
	}

	/** ===================== Login with OTP ===================== **/
	@Override
	@Transactional
	public Map<String, Object> loginWithOtp(LoginRequest request) {
	    String email = request.getEmail().trim().toLowerCase();
	    String enteredOtp = request.getOtp();

	    // Fetch ManageUsers
	    ManageUsers manageUser = manageUserRepository.findByEmailIgnoreCase(email)
	            .orElseThrow(() -> new RuntimeException("Invalid credentials: email not registered"));

	    // Validate OTP
	    OTP otpEntity = tokenRepository.findByEmailAndOtp(email, enteredOtp)
	            .orElseThrow(() -> new RuntimeException("Invalid OTP or email"));
	    if (System.currentTimeMillis() > otpEntity.getExpiryTime()) {
	        tokenRepository.deleteByEmail(email);
	        throw new RuntimeException("OTP has expired");
	    }
	    tokenRepository.deleteByEmail(email);

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

	@Override
	public Optional<User> getUserByEmail(String email) {
		return userRepository.findByEmailIgnoreCase(email);
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
	public UserProfileResponse getUserProfileByEmail(String email) {

		String normalizedEmail = email.trim().toLowerCase();

		Optional<User> userOpt = userRepository.findByEmailIgnoreCase(normalizedEmail);
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

	@Override
	@Transactional
	public boolean verifyOtp(String emailInput, String otpInput) {

		final String email = emailInput.trim().toLowerCase();

		// Fetch OTP record
		OTP otpEntity = tokenRepository.findByEmail(email)
				.orElseThrow(() -> new RuntimeException("OTP not found for this email"));

		// Check expiry
		if (System.currentTimeMillis() > otpEntity.getExpiryTime()) {
			tokenRepository.deleteByEmail(email); // remove expired OTP
			throw new RuntimeException("OTP has expired");
		}

		// Validate OTP
		if (!otpEntity.getOtp().equals(otpInput)) {
			throw new RuntimeException("Invalid OTP");
		}

		// OTP is valid → delete it after successful verification
		tokenRepository.deleteByEmail(email);

		return true;
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

	@Transactional
	@Override
	public void accountnumbersendOTP(String emailInput) {
		final String email = emailInput.trim().toLowerCase();

		// Fetch user
		User user = userRepository.findByEmailIgnoreCase(email)
				.orElseThrow(() -> new RuntimeException("Invalid credentials: email not registered"));

		// Build full name
		String fullName = (user.getFullName() != null && !user.getFullName().isBlank()) ? user.getFullName()
				: (user.getFirstName() != null ? user.getFirstName() : email.split("@")[0]);
		String safeFullname = HtmlUtils.htmlEscape(fullName);

		// Remove old OTPs
		tokenRepository.deleteByEmail(email);

		// ✅ Generate new ALPHANUMERIC OTP
		String otp = generateAlphanumericOTP(6); // 6-character alphanumeric OTP
		long expiryTime = System.currentTimeMillis() + 2 * 60_000; // 2 minutes

		OTP otpEntity = new OTP(null, email, otp, expiryTime);
		tokenRepository.save(otpEntity);

		// Send email with designed HTML
		try {
			MimeMessage mimeMessage = javaMailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);
			helper.setFrom(fromEmail);
			helper.setTo(email);
			helper.setSubject("Account Number Update Verification Code - Invoicing Team");
			String htmlContent = "<!DOCTYPE html>" + "<html>" + "<head><meta charset='UTF-8'></head>"
					+ "<body style='margin:0; padding:0; font-family: Arial, sans-serif; background-color:#f9f9f9;'>"
					+ "<table align='center' width='600' cellpadding='0' cellspacing='0' style='background:#ffffff; border-radius:8px; box-shadow:0 4px 8px rgba(0,0,0,0.1);'>"
					+ "<tr>"
					+ "<td align='center' bgcolor='#2563eb' style='padding:20px; border-top-left-radius:8px; border-top-right-radius:8px;'>"
					+ "<h2 style='color:#ffffff; margin:0;'> Invoice </h2>" + "</td>" + "</tr>" + "<tr>"
					+ "<td style='padding:30px;'>" + "<h3 style='color:#004b6e; margin-top:0;'>Invoicing Team</h3>"
					+ "<p style='font-size:16px; color:#4b5563;'>" + "Hello <strong>" + safeFullname
					+ "</strong>,<br><br>"

					+ "We received a request to <b>update your account number</b> in the <b>Invoicing Application</b>."
					+ "<br><br>For security purposes, please use the OTP below to verify this change." + "</p>"

					+ "<div style='text-align:center; margin:32px 0;'>"
					+ "<div style='display:inline-block; padding:18px 32px; border-radius:12px; border:2px dashed #2563eb; background:#eff6ff; font-size:36px; font-weight:700; letter-spacing:8px; color:#1e3a8a;'>"
					+ otp + "</div>" + "</div>"

					+ "<p style='text-align:center; font-size:15px; color:#6b7280;'>"
					+ "This OTP is valid for <strong>2 minutes</strong>. Please do not share this code with anyone."
					+ "</p>"

					+ "<p style='font-size:14px; color:#333; margin-top:20px;'>"
					+ "If you did not request this account number update, please contact our support team immediately."
					+ "</p>"

					+ "<p style='font-size:14px; color:#333; margin-top:30px;'>"
					+ "Best Regards,<br><b>Invoicing Team</b>" + "</p>"

					+ "</td>" + "</tr>" + "<tr>"
					+ "<td align='center' bgcolor='#f1f1f1' style='padding:10px; border-bottom-left-radius:8px; border-bottom-right-radius:8px; font-size:12px; color:#888;'>"
					+ "2026 Invoicing Team. All rights reserved." + "</td>" + "</tr>" + "</table>" + "</body>"
					+ "</html>";

			helper.setText(htmlContent, true);
			javaMailSender.send(mimeMessage);
			log.info("OTP sent successfully to {}", email);
		} catch (Exception e) {
			log.error("Failed to send OTP email to {}: {}", email, e.getMessage(), e);
		}
	}

}