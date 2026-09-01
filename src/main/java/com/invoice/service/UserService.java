package com.invoice.service;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.web.multipart.MultipartFile;

import com.invoice.DTO.LoginRequest;
import com.invoice.DTO.ManageUserDTO;
import com.invoice.DTO.SortingRequestDTO;
import com.invoice.DTO.UserProfileResponse;
import com.invoice.entity.ManageUsers;
import com.invoice.entity.Role;
import com.invoice.entity.User;

public interface UserService {

	public String register(User user);

	public Map<String, Object> loginWithOtp(LoginRequest request);

	public void sendOtp(String email);// request OTP

	public void accountnumbersendOTP(String emailInput);

	public Optional<User> getUserById(Long id);

	public User updateUserProfile(Long id, User updatedProfile);

	Optional<User> getUserByEmail(String email);

	Map<String, Object> getPrivilegesForUser(Long userId);

	UserProfileResponse getUserProfileByEmail(String email);

	public boolean verifyOtp(String emailInput, String otpInput);

	public void sendOtpForRegister(String emailInput);

	public ManageUserDTO registerCompanyUser(ManageUsers manageUsers);

}
