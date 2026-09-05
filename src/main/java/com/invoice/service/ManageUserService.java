package com.invoice.service;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.web.multipart.MultipartFile;

import com.invoice.DTO.ManageUserDTO;
import com.invoice.DTO.SortingRequestDTO;
import com.invoice.DTO.UserUpdateRequest;
import com.invoice.entity.ManageUsers;
import com.invoice.entity.User;

public interface ManageUserService {

	// Create a new ManageUser
	ManageUserDTO createUser(ManageUsers manageUsers, String loggedInEmail);

	
	// Update ManageUser
	ManageUserDTO updateUser(Long id, ManageUsers manageUsers, String loggedInEmail);

	// Delete ManageUser
	void deleteUser(Long id, String loggedInEmail);

	// Get all users (list)
	List<ManageUserDTO> getAllUsers(String loggedInEmail);

	// Get user by ID
	ManageUserDTO getById(Long id);

	// Get user by email
	ManageUserDTO getByEmail(String email);

	// Get user by ID with respect to logged-in user permissions
	ManageUserDTO getByIdAndLoggedInUser(Long id, String loggedInEmail);

	// Pagination + Search
	public Page<ManageUserDTO> getAllUsersWithPaginationAndSearch(int page, int size, String sortField, String sortDir,
			String keyword);

	// Pagination + Search scoped to a specific tenant adminId
	public Page<ManageUserDTO> getAllUsersWithPaginationAndSearch(int page, int size, String sortField, String sortDir,
			String keyword, Long adminId);

	// Update user profile
	User updateUserProfile(UserUpdateRequest request, MultipartFile profileImage, String loggedInEmail);

	// Upload profile file
	String uploadFile(MultipartFile file, Long userId) throws IOException;

	// Bhagi

	User updateUserProfileDynamic(UserUpdateRequest request);

	/**
	 * Updates the caller's own profile (PUT /auth/updated/save). The target is
	 * the authenticated user, never the body's {@code id}; a bank-account change
	 * needs a verified ACCOUNT_NUMBER_CHANGE code.
	 */
	User updateOwnProfile(UserUpdateRequest request, String callerEmail, com.invoice.otp.OtpRequestContext context);

	
	UserUpdateRequest mapToDto(User user);

	// Bhagi
	

	/** ================= UPDATE USER PROFILE ================= **/

	User updateUserProfile(UserUpdateRequest request, String loggedInEmail);

	Page<ManageUserDTO> getAllManageUsersWithSorting(SortingRequestDTO sortingRequestDTO, String loggedInEmail);

	Optional<ManageUsers> findByAdminId(Long adminId);
	
}
