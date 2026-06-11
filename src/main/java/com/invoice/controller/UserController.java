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

	/** Send OTP */
	@PostMapping("/login/send-otp")
	public ResponseEntity<RestAPIResponse> sendOTP(@RequestBody Map<String, String> body) {
		try {
			String email = body.get("email");
			userServiceImpl.sendOtp(email);
			return ResponseEntity.ok(new RestAPIResponse("success", "OTP sent successfully", email));
		} catch (Exception e) {
			return ResponseEntity.badRequest().body(new RestAPIResponse("error", e.getMessage(), null));
		}
	}

	@PostMapping("/register/send-otp")
	public ResponseEntity<RestAPIResponse> sendRegisterOtp(@RequestBody Map<String, String> body) {
		try {
			String email = body.get("email");
			userServiceImpl.sendOtpForRegister(email);
			return ResponseEntity.ok(new RestAPIResponse("success", "OTP sent successfully for registration", email));
		} catch (Exception e) {
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
	public ResponseEntity<RestAPIResponse> login(@RequestBody LoginRequest request) {
		try {
			Map<String, Object> jwtToken = userServiceImpl.loginWithOtp(request);
			return ResponseEntity.ok(new RestAPIResponse("success", "Login Successfully", jwtToken));
		} catch (Exception e) {
			return ResponseEntity.badRequest().body(new RestAPIResponse("error", e.getMessage(), null));
		}
	}

	/** Verify OTP */
	@PostMapping("/login/verify-otp")
	public ResponseEntity<RestAPIResponse> verifyOTP(@RequestBody VerifyOtpRequest request) {
		try {
			boolean isValid = userServiceImpl.verifyOtp(request.getEmail(), request.getOtp());

			if (isValid) {
				return ResponseEntity.ok(new RestAPIResponse("success", "OTP verified successfully", null));
			} else {
				return ResponseEntity.badRequest().body(new RestAPIResponse("error", "Invalid or expired OTP", null));
			}

		} catch (Exception e) {
			return ResponseEntity.badRequest().body(new RestAPIResponse("error", e.getMessage(), null));
		}
	}

	@PostMapping("/accountnumbersend-otp")
	public ResponseEntity<RestAPIResponse> accountnumbersendOTP(@RequestBody Map<String, String> body) {
		try {
			String email = body.get("email");
			userServiceImpl.accountnumbersendOTP(email);
			return ResponseEntity.ok(new RestAPIResponse("success", "OTP sent successfully", email));
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

	@GetMapping("/updated/email/{email}")
	public ResponseEntity<RestAPIResponse> getUserProfileByEmail(@PathVariable("email") String email) {

		UserProfileResponse response = userServiceImpl.getUserProfileByEmail(email);

		if (response == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(new RestAPIResponse("Fail", "No user found with this email: " + email, Map.of()));
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
