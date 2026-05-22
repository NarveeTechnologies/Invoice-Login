package com.invoice.controller;

import java.io.IOException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.invoice.DTO.ManageUserDTO;
import com.invoice.DTO.SortingRequestDTO;
import com.invoice.DTO.UserUpdateRequest;
import com.invoice.commons.RestAPIResponse;
import com.invoice.entity.ManageUsers;
import com.invoice.entity.User;
import com.invoice.repository.ManageUserRepository;
import com.invoice.repository.UserRepository;
import com.invoice.service.ManageUserService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class ManageUsersController {

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ManageUserRepository manageuserrepository;

	@Autowired
	private ManageUserService manageUsersService;

	// 🔹 Create user (accessible by SUPERADMIN or ADMIN)
	@PreAuthorize("hasAuthority('USER_CREATE')")
	@PostMapping("/manageusers/save")
	public ResponseEntity<RestAPIResponse> createUser(@RequestBody ManageUsers manageUsers,
			Authentication authentication) {
		String loggedInEmail = authentication.getName();
		ManageUserDTO savedUser = manageUsersService.createUser(manageUsers, loggedInEmail);
		return ResponseEntity.ok(new RestAPIResponse("Success", "User created successfully", savedUser));
	}

	@PutMapping("/manageusers/{id}")
	public ResponseEntity<RestAPIResponse> updateUser(@PathVariable Long id, @RequestBody ManageUsers manageUsers,
			Authentication authentication) {

		String loggedInEmail = authentication.getName();
		ManageUserDTO updatedUser = manageUsersService.updateUser(id, manageUsers, loggedInEmail);

		return ResponseEntity.ok(new RestAPIResponse("Success", "User updated successfully", updatedUser));
	}

	@PutMapping("/updated/save")
	public ResponseEntity<?> updateUser(@RequestBody UserUpdateRequest request) {
		User updatedUser = manageUsersService.updateUserProfileDynamic(request);
		return ResponseEntity.ok(updatedUser);
	}

	// 🔹 Get available roles for dropdowns (UI helper)
	@GetMapping("/manageusers/roles")
	public ResponseEntity<RestAPIResponse> getAllRolesForSelection() {
		List<String> roles = List.of("SUPERADMIN", "ADMIN", "ACCOUNTANT", "DEVELOPER");
		return ResponseEntity.ok(new RestAPIResponse("Success", "Roles fetched successfully", roles));
	}

	@GetMapping("/manageusers/searchAndsorting")
	public ResponseEntity<RestAPIResponse> getAllUsersWithPaginationAndSearch(
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size,
			@RequestParam(defaultValue = "id") String sortField, @RequestParam(defaultValue = "asc") String sortDir,
			@RequestParam(required = false) String keyword) {

		Page<ManageUserDTO> userPage = manageUsersService.getAllUsersWithPaginationAndSearch(page, size, sortField,
				sortDir, keyword);

		Map<String, Object> response = new HashMap<>();
		response.put("users", userPage.getContent());
		response.put("currentPage", userPage.getNumber());
		response.put("totalItems", userPage.getTotalElements());
		response.put("totalPages", userPage.getTotalPages());
		response.put("sortField", sortField);
		response.put("sortDir", sortDir);
		response.put("keyword", keyword);

		return ResponseEntity
				.ok(new RestAPIResponse("Success", "Users fetched successfully with pagination", response));
	}

	@GetMapping("/manageusers/getall")
	public ResponseEntity<RestAPIResponse> getAllUsers(Authentication authentication) {
		String loggedInEmail = authentication.getName();
		List<ManageUserDTO> users = manageUsersService.getAllUsers(loggedInEmail);

		return ResponseEntity.ok(new RestAPIResponse("Success", "Users fetched successfully", users));
	}

	// 🔹 Get logged-in user’s own data
	@GetMapping("/manageusers/me")
	public ResponseEntity<RestAPIResponse> getMyProfile(Authentication authentication) {
		String loggedInEmail = authentication.getName();
		ManageUserDTO user = manageUsersService.getByEmail(loggedInEmail);

		return ResponseEntity.ok(new RestAPIResponse("Success", "Your profile fetched successfully", user));
	}

	// 🔹 Get specific user by ID (visible based on access rules)
	@GetMapping("/manageusers/{id}")
	public ResponseEntity<RestAPIResponse> getUserById(@PathVariable Long id, Authentication authentication) {

		String loggedInEmail = authentication.getName();
		ManageUserDTO user = manageUsersService.getByIdAndLoggedInUser(id, loggedInEmail);

		return ResponseEntity.ok(new RestAPIResponse("Success", "User retrieved successfully", user));
	}

	@DeleteMapping("/manageusers/{id}")
	public ResponseEntity<RestAPIResponse> deleteUser(@PathVariable Long id, Authentication authentication) {

		String loggedInEmail = authentication.getName();
		manageUsersService.deleteUser(id, loggedInEmail);

		return ResponseEntity.ok(new RestAPIResponse("Success", "User deleted successfully", null));
	}

	@PostMapping("/manageusers/searchAndsorting/getall")
	public ResponseEntity<RestAPIResponse> getManageUsersList(@RequestBody SortingRequestDTO sortingRequestDTO,
			Authentication authentication) {

		String loggedInEmail = authentication.getName();

		// ✅ Pass loggedInEmail to service method
		Page<ManageUserDTO> manageUsers = manageUsersService.getAllManageUsersWithSorting(sortingRequestDTO,
				loggedInEmail);

		return new ResponseEntity<>(
				new RestAPIResponse("success", "Successfully retrieved manage users list", manageUsers), HttpStatus.OK);
	}

}
