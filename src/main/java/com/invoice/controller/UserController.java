package com.invoice.controller;

import java.io.IOException;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoice.DTO.LoginRequest;
import com.invoice.DTO.ManageUserDTO;
import com.invoice.DTO.RegisterRequest;
import com.invoice.DTO.UserProfileResponse;
import com.invoice.commons.RestAPIResponse;
import com.invoice.entity.CompanyRegistry;
import com.invoice.entity.ManageUsers;
import com.invoice.entity.Privilege;
import com.invoice.entity.Role;
import com.invoice.entity.User;
import com.invoice.entity.VerifyOtpRequest;
import com.invoice.exception.BusinessException;
import com.invoice.repository.CompanyRegistryRepository;
import com.invoice.repository.ManageUserRepository;
import com.invoice.repository.PrivilegeRepository;
import com.invoice.repository.RoleRepository;
import com.invoice.repository.UserRepository;
import com.invoice.service.FileStorageService;
import com.invoice.service.UserService;
import com.invoice.serviceImpl.JwtServiceImpl;
import com.invoice.serviceImpl.UserServiceImpl;
import com.invoice.tenant.SchemaProvisioningService;
import com.invoice.tenant.SecurityUtils;
import com.invoice.tenant.TenantContext;
import com.invoice.utils.JwtUtil;

import jakarta.servlet.http.HttpServletRequest;

//@CrossOrigin("*")
@RestController
@RequestMapping("/auth")
@lombok.extern.slf4j.Slf4j
public class UserController {

	@Autowired
	private com.invoice.otp.ClientIpResolver clientIpResolver;

	/**
	 * Uniform acknowledgement for every passcode request.
	 *
	 * <p>Returned whether or not the address has an account, so that the
	 * response body, the status code and the shape of the answer carry no
	 * signal. The correlation id is safe to hand back: it identifies the
	 * request in the logs and cannot be turned into a passcode.
	 */
	private static final String OTP_SENT_MESSAGE =
			"If that email address has an Invoice account, a verification code is on its way.";


	@Autowired
	private UserServiceImpl userServiceImpl;

	@Autowired
	private JwtServiceImpl jwtService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ManageUserRepository manageUserRepository;

	@Autowired
	private UserService userService;

	@Autowired
	private RoleRepository roleRepository;

	@Autowired
	private JwtUtil jwtUtil;

	@Autowired
	private FileStorageService fileStorageService;

	@Autowired
	private SchemaProvisioningService schemaProvisioningService;

	@Autowired
	private CompanyRegistryRepository companyRegistryRepository;
	
	@Autowired
	private PrivilegeRepository privilegeRepository;
	
	@PostMapping(value = "/register", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<RestAPIResponse> register(@RequestPart("data") String data,
	        @RequestPart(value = "companylogo", required = false) MultipartFile companylogo,
	        HttpServletRequest request) {

	    try {
	        ObjectMapper objectMapper = new ObjectMapper();
	        RegisterRequest requestObj = objectMapper.readValue(data, RegisterRequest.class);

	        if (requestObj.getEmail() == null || requestObj.getEmail().isBlank()) {
	            return ResponseEntity.badRequest()
	                    .body(new RestAPIResponse("failed", "Email is required", null));
	        }

	        ManageUsers manageUsers = userServiceImpl.buildManageUsersFromRequest(requestObj);

	        if (companylogo != null && !companylogo.isEmpty()) {
	            String fileName = fileStorageService.saveFile(companylogo);
	            manageUsers.setCompanylogo(fileName);
	        }

	        ManageUserDTO response = userServiceImpl.registerCompanyUser(manageUsers);

	        try {
	            schemaProvisioningService.provisionTenantSchema(response.getCompanyDomain());
	        } catch (Exception e) {
	            log.error("Schema provisioning failed for domain {}", response.getCompanyDomain(), e);
	        }

	        try {
	            String schemaName = TenantContext.toSchemaName(response.getCompanyDomain());
	            String savedLogo = manageUsers.getCompanylogo();
	            if (!companyRegistryRepository.existsByCompanyDomain(response.getCompanyDomain())) {
	                companyRegistryRepository.save(new CompanyRegistry(response.getCompanyName(),
	                        response.getCompanyDomain(), schemaName, response.getEmail(), savedLogo));
	            }
	        } catch (Exception e) {
	            log.error("Company registry save failed for domain {}", response.getCompanyDomain(), e);
	        }

	        User user = userRepository.findByEmailIgnoreCase(response.getEmail())
	                .orElseThrow(() -> new RuntimeException("User not found"));

	        ManageUsers savedUser = manageUserRepository.findByEmailIgnoreCase(response.getEmail())
	                .orElseThrow(() -> new RuntimeException("ManageUser not found"));

	        Long roleId = savedUser.getRole().getRoleId();
	        String roleName = null;
	        Set<String> privilegeNames = new HashSet<>();

	        // ✅ Use JOIN FETCH to load all privileges eagerly — avoids stale cache issue
	        if (roleId != null) {
	        	Role roleEntity = roleRepository.findByIdWithPrivileges(roleId).orElse(null);
	        	if (roleEntity != null) {
	        	    roleName = roleEntity.getRoleName();
	        	    privilegeNames = roleEntity.getPrivileges()
	        	            .stream()
	        	            .map(Privilege::getName)
	        	            .collect(Collectors.toSet());
	        	}
	        }

	        Long tenantAdminId = savedUser.getAdminId() != null ? savedUser.getAdminId() : user.getId();
	        String token = jwtService.generateToken(user, tenantAdminId, roleName, privilegeNames);

	        String baseUrl = request.getScheme() + "://" + request.getServerName()
	                + ":" + request.getServerPort();

	        String logoUrl = null;
	        if (savedUser.getCompanylogo() != null) {
	            logoUrl = baseUrl + "/uploads/" + savedUser.getCompanylogo();
	        }

	        Map<String, Object> finalResponse = new LinkedHashMap<>();
	        finalResponse.put("id", savedUser.getId());
	        finalResponse.put("fullName", savedUser.getFullName());
	        finalResponse.put("firstName", savedUser.getFirstName());
	        finalResponse.put("middleName", savedUser.getMiddleName());
	        finalResponse.put("lastName", savedUser.getLastName());
	        finalResponse.put("email", savedUser.getEmail());
	        finalResponse.put("mobileNumber", savedUser.getMobileNumber());
	        finalResponse.put("companyName", savedUser.getCompanyName());
	        finalResponse.put("state", savedUser.getState());
	        finalResponse.put("city", savedUser.getCity());
	        finalResponse.put("country", savedUser.getCountry());
	        finalResponse.put("pincode", savedUser.getPincode());
	        finalResponse.put("telephone", savedUser.getTelephone());
	        finalResponse.put("ein", savedUser.getEin());
	        finalResponse.put("gstin", savedUser.getGstin());
	        finalResponse.put("website", savedUser.getWebsite());
	        finalResponse.put("address", savedUser.getAddress());
	        finalResponse.put("loginurl", savedUser.getLoginUrl());
	        finalResponse.put("businessCountry", savedUser.getBusinessCountry());
	        finalResponse.put("suite", savedUser.getSuite());
	        finalResponse.put("roleName", roleName);
	        finalResponse.put("privileges", privilegeNames);
	        finalResponse.put("companylogo", logoUrl);
	        finalResponse.put("adminId", savedUser.getAdminId());
	        finalResponse.put("companydomain", savedUser.getCompanyDomain());
	        finalResponse.put("token", token);

	        return ResponseEntity.status(HttpStatus.CREATED).body(
	                new RestAPIResponse("success", "Company registered successfully. ADMIN created.",
	                        finalResponse));

	    } catch (DataIntegrityViolationException e) {
	        String errorMsg;
	        String rootMsg = e.getMostSpecificCause().getMessage();

	        if (rootMsg != null && rootMsg.contains("duplicate key")) {
	            if (rootMsg.contains("manage_users_email_key") || rootMsg.contains("email")) {
	                errorMsg = "Email already registered. Please use a different email.";
	            } else if (rootMsg.contains("roles_pkey")) {
	                errorMsg = "Role creation failed due to sequence conflict. Please try again.";
	            } else if (rootMsg.contains("unique constraint")) {
	                errorMsg = "Duplicate entry detected. Record already exists.";
	            } else {
	                errorMsg = "Duplicate entry detected. Please check your input.";
	            }
	        } else if (rootMsg != null && rootMsg.contains("foreign key")) {
	            errorMsg = "Invalid reference. Related record not found.";
	        } else if (rootMsg != null && rootMsg.contains("not-null")) {
	            errorMsg = "Required field is missing. Please fill all mandatory fields.";
	        } else {
	            errorMsg = "Data integrity error. Please check your input.";
	        }

	        return ResponseEntity.status(HttpStatus.CONFLICT)
	                .body(new RestAPIResponse("failed", errorMsg, null));

	    } catch (BusinessException e) {
	        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
	                .body(new RestAPIResponse("failed", e.getMessage(), null));

	    } catch (Exception e) {
	        log.error("Registration failed", e);
	        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
	                .body(new RestAPIResponse("failed", "Registration failed: " + e.getMessage(), null));
	    }
	}

	/**
	 * Requests a sign-in passcode.
	 *
	 * <p>Answers identically for a registered and an unregistered address. It
	 * used to return the service exception verbatim, so an unregistered address
	 * produced 400 "Invalid credentials: email not registered" — an
	 * account-existence oracle on an endpoint that needs no authentication.
	 */
	@PostMapping("/login/send-otp")
	public ResponseEntity<RestAPIResponse> sendOTP(@RequestBody Map<String, String> body,
			HttpServletRequest request) {
		String email = body.get("email");
		if (email == null || email.isBlank()) {
			return ResponseEntity.badRequest()
					.body(new RestAPIResponse("error", "An email address is required", null));
		}
		try {
			userServiceImpl.sendOtp(email, clientIpResolver.contextOf(request));
		} catch (com.invoice.exception.MailDeliveryException | com.invoice.otp.OtpRateLimitedException e) {
			// Delivery failure (503) and rate limiting (429) are answered by
			// GlobalExceptionHandler. Swallowing either here would report success
			// for a passcode that was never sent.
			throw e;
		} catch (Exception e) {
			// Anything else is logged and absorbed into the uniform answer. A
			// distinct response here is exactly what made this endpoint an oracle.
			log.warn("send-otp failed for a login request", e);
		}
		return ResponseEntity.accepted()
				.body(new RestAPIResponse("success", OTP_SENT_MESSAGE, null));
	}

	@PostMapping("/register/send-otp")
	public ResponseEntity<RestAPIResponse> sendRegisterOtp(@RequestBody Map<String, String> body,
			HttpServletRequest request) {
		try {
			String email = body.get("email");
			userServiceImpl.sendOtpForRegister(email, clientIpResolver.contextOf(request));
			return ResponseEntity.ok(new RestAPIResponse("success", "OTP sent successfully for registration", email));
		} catch (com.invoice.exception.MailDeliveryException | com.invoice.otp.OtpRateLimitedException e) {
			throw e;
		} catch (Exception e) {
			// Registration deliberately reports an existing-account collision:
			// the user has to be told to sign in instead. See
			// docs/INVOICE_OTP_SECURITY.md on why this differs from login.
			return ResponseEntity.badRequest().body(new RestAPIResponse("error", e.getMessage(), null));
		}
	}

	@GetMapping("/check-email/{email}")
	public ResponseEntity<RestAPIResponse> checkDuplicateEmail(@PathVariable String email) {
		// logger.info("!!! inside class: CustomersController,!! method:
		// checkDuplicateEmail() ");
		boolean isDuplicate = userServiceImpl.isEmailDuplicate(email);

		if (isDuplicate) {
			return new ResponseEntity<RestAPIResponse>(new RestAPIResponse("fail", "Email already exists", isDuplicate),
					HttpStatus.OK);
		} else {
			return new ResponseEntity<RestAPIResponse>(
					new RestAPIResponse("success", "Email is available", isDuplicate), HttpStatus.OK);
		}
	}

	/** Login → OTP & return JWT */
	@PostMapping("/login")
	public ResponseEntity<RestAPIResponse> login(@RequestBody LoginRequest request,
			HttpServletRequest httpRequest) {
		try {
			Map<String, Object> jwtToken =
					userServiceImpl.loginWithOtp(request, clientIpResolver.contextOf(httpRequest));
			return ResponseEntity.ok(new RestAPIResponse("success", "Login Successfully", jwtToken));
		} catch (Exception e) {
			return ResponseEntity.badRequest().body(new RestAPIResponse("error", e.getMessage(), null));
		}
	}

	/** Verify OTP */
	@PostMapping("/login/verify-otp")
	public ResponseEntity<RestAPIResponse> verifyOTP(@RequestBody VerifyOtpRequest request,
			HttpServletRequest httpRequest) {
		try {
			boolean isValid = userServiceImpl.verifyOtp(request.getEmail(), request.getOtp(),
					clientIpResolver.contextOf(httpRequest));

			if (isValid) {
				return ResponseEntity.ok(new RestAPIResponse("success", "OTP verified successfully", null));
			}
			// One message for every failure. Distinguishing "no code outstanding"
			// from "wrong code" from "expired" told an unauthenticated caller
			// which address to keep working on.
			return ResponseEntity.badRequest().body(new RestAPIResponse("error",
					com.invoice.otp.OtpVerificationResult.userFacingFailureMessage(), null));

		} catch (com.invoice.otp.OtpRateLimitedException e) {
			throw e;
		} catch (Exception e) {
			log.warn("verify-otp failed", e);
			return ResponseEntity.badRequest().body(new RestAPIResponse("error",
					com.invoice.otp.OtpVerificationResult.userFacingFailureMessage(), null));
		}
	}

	/**
	 * Verifies a registration passcode.
	 *
	 * <p>Distinct from {@code /auth/login/verify-otp}: that one binds LOGIN, and
	 * a code minted for registration must not be spendable as a sign-in.
	 */
	@PostMapping("/register/verify-otp")
	public ResponseEntity<RestAPIResponse> verifyRegisterOtp(@RequestBody VerifyOtpRequest request,
			HttpServletRequest httpRequest) {
		try {
			boolean isValid = userServiceImpl.verifyRegistrationOtp(request.getEmail(),
					request.getOtp(), clientIpResolver.contextOf(httpRequest));
			if (isValid) {
				return ResponseEntity.ok(
						new RestAPIResponse("success", "Email verified successfully", null));
			}
			return ResponseEntity.badRequest().body(new RestAPIResponse("error",
					com.invoice.otp.OtpVerificationResult.userFacingFailureMessage(), null));
		} catch (com.invoice.otp.OtpRateLimitedException e) {
			throw e;
		} catch (Exception e) {
			log.warn("register verify-otp failed", e);
			return ResponseEntity.badRequest().body(new RestAPIResponse("error",
					com.invoice.otp.OtpVerificationResult.userFacingFailureMessage(), null));
		}
	}

	/**
	 * Re-authenticates the signed-in user before a bank-detail change.
	 *
	 * <p>The address comes from the <strong>token</strong>, not the body. It used
	 * to be taken from {@code body.get("email")} with no check that it belonged
	 * to the caller, so any authenticated user could make the service send OTP
	 * mail to any address on the platform. Verified: a tenant-1001 session sent
	 * a passcode to a tenant-900 user — the victim's inbox went from 3 messages
	 * to 4 and an {@code ACCOUNT_NUMBER_CHANGE} challenge was created against
	 * their account.
	 *
	 * <p>That is worth more than the nuisance it looks like. It delivers a
	 * genuine, correctly-branded security email on demand to a chosen victim,
	 * which is the setup for "we noticed a bank change on your account, confirm
	 * the code" — and it spends the victim's own resend allowance, so their real
	 * bank-detail change starts failing on a rate limit they never used.
	 *
	 * <p>This endpoint only ever needs the caller's own address: its whole
	 * purpose is to re-verify the person already holding the session. A body
	 * address is accepted but must match, so an existing client that still sends
	 * one keeps working while a mismatched one is refused.
	 */
	@PostMapping("/accountnumbersend-otp")
	public ResponseEntity<RestAPIResponse> accountnumbersendOTP(@RequestBody Map<String, String> body,
			@RequestHeader("Authorization") String token, HttpServletRequest request) {
		final String callerEmail;
		try {
			callerEmail = jwtService.extractUsername(token.replace("Bearer", "").trim());
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.body(new RestAPIResponse("error", "Invalid token", null));
		}

		String requested = body.get("email");
		if (requested != null && !requested.isBlank()
				&& !requested.trim().equalsIgnoreCase(callerEmail)) {
			log.warn("Refused account-number OTP for a different address: caller={} requested={}",
					callerEmail, requested.trim());
			return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new RestAPIResponse("error",
					"A verification code can only be sent to your own email address.", null));
		}

		try {
			userServiceImpl.accountnumbersendOTP(callerEmail, clientIpResolver.contextOf(request));
			return ResponseEntity.ok(new RestAPIResponse("success", "OTP sent successfully", callerEmail));
		} catch (com.invoice.exception.MailDeliveryException e) {
			// Delivery failure is not a bad request. Let GlobalExceptionHandler answer
			// 503 so the caller knows to retry; swallowing it here would report success
			// for a passcode that was never sent.
			throw e;
		} catch (Exception e) {
			return ResponseEntity.badRequest().body(new RestAPIResponse("error", e.getMessage(), null));
		}
	}

	/** Check token validity */
	@GetMapping("/check-token")
	public ResponseEntity<RestAPIResponse> checkToken(@RequestParam String token) {
		boolean isValid = jwtService.validateToken(token);
		String username = isValid ? jwtService.extractUsername(token) : null;

		return ResponseEntity.ok(new RestAPIResponse(isValid ? "success" : "error",
				isValid ? "Token is valid" : "Token is invalid", username));
	}

	/**
	 * A user's profile, by email address.
	 *
	 * <p><strong>This endpoint had no authorization check at all.</strong> It took
	 * an arbitrary address from the path and returned that person's
	 * {@link UserProfileResponse} — which carries {@code bankDetails}, {@code taxId},
	 * {@code ein}, {@code gstin}, address and telephone. Any authenticated user
	 * could read any other user's banking and tax details by putting their email
	 * in the URL. Verified against the running service: a user in one tenant
	 * retrieved another tenant's account number with HTTP 200.
	 *
	 * <p>The caller may now read their own profile, or one inside their own
	 * tenant. The tenant is the {@code adminId} claim, which
	 * {@code JwtServiceImpl.generateToken} refuses to omit, so it cannot be
	 * absent from a valid token — and it comes from the signed token rather than
	 * from anything the browser can set.
	 *
	 * <p>Same-tenant access is kept because the manage-users dialog legitimately
	 * loads a colleague's profile; the other four Angular call sites pass the
	 * caller's own address.
	 */
	@GetMapping("/updated/email/{email}")
	public ResponseEntity<RestAPIResponse> getUserProfileByEmail(@PathVariable("email") String email,
			@RequestHeader("Authorization") String token) {

		final String callerEmail;
		final Long callerTenant;
		try {
			String jwt = token.replace("Bearer", "").trim();
			callerEmail = jwtService.extractUsername(jwt);
			callerTenant = jwtService.extractAdminId(jwt);
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.body(new RestAPIResponse("Fail", "Invalid token", null));
		}

		String requested = email == null ? "" : email.trim();
		boolean ownProfile = requested.equalsIgnoreCase(callerEmail);

		if (!ownProfile && !userServiceImpl.isInTenant(requested, callerTenant)) {
			// Deliberately the same answer as "no such user": confirming that an
			// address exists elsewhere on the platform is itself a disclosure.
			log.warn("Cross-tenant profile read refused: caller={} tenant={} requested={}",
					callerEmail, callerTenant, requested);
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(new RestAPIResponse("Fail", "No user found with this email: " + requested, Map.of()));
		}

		UserProfileResponse response = userServiceImpl.getUserProfileByEmail(requested);

		if (response == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(new RestAPIResponse("Fail", "No user found with this email: " + requested, Map.of()));
		}

		return ResponseEntity.ok(new RestAPIResponse("Success", "Profile retrieved successfully", response));
	}

	@GetMapping("/me")
	public ResponseEntity<RestAPIResponse> getMyProfile(@RequestHeader("Authorization") String token) {
		try {
			String jwtToken = token.replace("Bearer", "");
			String email = jwtService.extractUsername(jwtToken);

			User user = userServiceImpl.getUserByEmail(email).orElseThrow(() -> new RuntimeException("user not found"));

			return ResponseEntity.ok(new RestAPIResponse("Success", "Profile Fetched Successfully", user));
		} catch (Exception e) {
			return ResponseEntity.ok(new RestAPIResponse("Error", e.getMessage(), null));
		}
	}

	@GetMapping("/getall/privileges")
	public ResponseEntity<RestAPIResponse> getMyPrivileges(@RequestHeader("Authorization") String token) {
		try {
			String jwtToken = token.replace("Bearer ", "").trim();
			String email = jwtService.extractUsername(jwtToken);
			User user = userServiceImpl.getUserByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));

			Map<String, Object> privileges = userServiceImpl.getPrivilegesForUser(user.getId());
			return ResponseEntity.ok(new RestAPIResponse("success", "Privileges fetched successfully", privileges));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(new RestAPIResponse("error", e.getMessage(), null));
		}
	}

	@PutMapping("/me")
	public ResponseEntity<RestAPIResponse> updateMyProfile(@RequestHeader("Authorization") String token,
			@RequestBody User updatedProfile) {
		try {
			String jwtToken = token.replace("Bearer", "");
			String email = jwtService.extractUsername(jwtToken);

			User existingUser = userServiceImpl.getUserByEmail(email)
					.orElseThrow(() -> new RuntimeException("user not found"));

			User updated = userServiceImpl.updateUserProfile(existingUser.getId(), updatedProfile);
			return ResponseEntity.ok(new RestAPIResponse("Success", "Profile Updated Successfully", updated));
		} catch (Exception e) {
			return ResponseEntity.ok(new RestAPIResponse("Error", e.getMessage(), null));
		}
	}

	/**
	 * Generate registration token
	 */
	@GetMapping("/get-registration-token")
	public ResponseEntity<RestAPIResponse> getRegistrationToken() {

		String token = jwtUtil.generateToken("REGISTRATION_SERVICE", "REGISTRATION");

		Map<String, String> tokenData = new HashMap<>();
		tokenData.put("token", token);
		tokenData.put("type", " ");
		tokenData.put("expiresIn", "24 hours");
		return ResponseEntity.ok(new RestAPIResponse("success", "Registration token generated", tokenData));
	}

	// Preview image in Postman
	@GetMapping("/{filename:.+}")
	public ResponseEntity<Resource> getFile(@PathVariable String filename) {
		try {
			// Serve only files the caller's own tenant references. Uploads are
			// named with a UUID, which makes a name hard to guess but is not an
			// access control -- and a name leaks as soon as it appears in a
			// response, a log or a referrer header.
			//
			// The containment check that stops "../.." escaping the upload
			// directory lives in loadFile; this is the tenant layer above it.
			if (!fileStorageService.isFileVisibleToTenant(filename,
					com.invoice.tenant.SecurityUtils.getCurrentAdminId())) {
				return ResponseEntity.notFound().build();
			}

			Resource resource = fileStorageService.loadFile(filename);

			// Detect content type dynamically
			String contentType = Files.probeContentType(resource.getFile().toPath());
			if (contentType == null) {
				contentType = "application/octet-stream";
			}

			return ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType))
					.header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
					.body(resource);

		} catch (IOException e) {
			return ResponseEntity.notFound().build();
		}
	}

	@PutMapping(value = "/logo/{adminId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<?> updateLogo(@PathVariable Long adminId, @RequestParam("companylogo") MultipartFile file) {
		try {

			// Enforce tenant isolation: caller must own this adminId
			SecurityUtils.assertOwnedByCurrentTenant(adminId);
			Long currentAdminId = SecurityUtils.getCurrentAdminId();

			if (file == null || file.isEmpty()) {
				return ResponseEntity.badRequest()
						.body(Map.of("status", "FAILED", "message", "File is empty or not provided"));
			}

			String filename = fileStorageService.updateLogo(currentAdminId, file);

			return ResponseEntity
					.ok(Map.of("status", "SUCCESS", "message", "Logo updated successfully", "fileName", filename));

		} catch (Exception e) {

			return ResponseEntity.badRequest().body(Map.of("status", "FAILED", "message", e.getMessage()));
		}
	}

	
}
