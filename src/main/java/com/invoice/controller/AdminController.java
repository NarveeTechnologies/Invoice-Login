package com.invoice.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.invoice.commons.RestAPIResponse;
import com.invoice.entity.Admin;
import com.invoice.entity.User;
import com.invoice.serviceImpl.AdminServiceImpl;
import com.invoice.tenant.SecurityUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/auth")
@PreAuthorize("isAuthenticated()")
public class AdminController {

	@Autowired
	private AdminServiceImpl adminServiceImpl;

	@PostMapping("/updated/save")
	public ResponseEntity<RestAPIResponse> saveUpdatedProfile(@RequestBody Admin admin) {
		try {
			// Ensure an authenticated tenant is present; service layer scopes by tenant
			SecurityUtils.getCurrentAdminId();
			Admin savedAdmin = adminServiceImpl.saveProfile(admin);
			return new ResponseEntity<>(new RestAPIResponse("Success", "Profile saved successfully", savedAdmin),
					HttpStatus.OK);
		} catch (Exception e) {
			return new ResponseEntity<>(new RestAPIResponse("Fail", "Profile not saved", null),
					HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	@GetMapping("/updated/getall")
	public ResponseEntity<RestAPIResponse> getAll() {
		try {
			// Touch SecurityUtils so a misconfigured filter chain fails fast
			Long adminId = SecurityUtils.getCurrentAdminId();
			// Scoped. This called adminServiceImpl.getAll() -- findAll() -- and
			// returned every tenant's profile, including taxId and businessId.
			// getCurrentAdminId() was called here and its result thrown away.
			return new ResponseEntity<>(
					new RestAPIResponse("Success", "All profiles retrieved successfully",
							adminServiceImpl.getAllForTenant(adminId)),
					HttpStatus.OK);
		} catch (Exception e) {
			log.error("Failed to list admin profiles", e);
			return new ResponseEntity<>(new RestAPIResponse("Fail", "No profiles found", null),
					HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	@GetMapping("/updated/{id}")
	public ResponseEntity<RestAPIResponse> getProfile(@PathVariable Long id) {

		// Tenant-scope: caller can only fetch their own admin profile
		Long currentAdminId = SecurityUtils.getCurrentAdminId();
		SecurityUtils.assertOwnedByCurrentTenant(id);

		User admin = adminServiceImpl.getById(currentAdminId);
		if (admin == null) {
			return new ResponseEntity<>(new RestAPIResponse("Fail", "Profile not found with ID: " + id, null),
					HttpStatus.NOT_FOUND);
		}
		return new ResponseEntity<>(new RestAPIResponse("Success", "Profile retrieved successfully", admin),
				HttpStatus.OK);
	}

	@PutMapping("/updated/{id}")
	public ResponseEntity<RestAPIResponse> updatedProfile(@PathVariable Long id, @RequestBody Admin admin) {
		try {
			// Tenant-scope: caller can only update their own admin profile
			Long currentAdminId = SecurityUtils.getCurrentAdminId();
			SecurityUtils.assertOwnedByCurrentTenant(id);

			Admin updatedAdmin = adminServiceImpl.updateProfile(currentAdminId, admin);
			log.error("{}", admin);
			if (updatedAdmin == null) {
				log.error("{}", updatedAdmin);
				return new ResponseEntity<>(new RestAPIResponse("Fail", "Profile not found with ID: " + id, null),
						HttpStatus.NOT_FOUND);
			}
			return new ResponseEntity<>(new RestAPIResponse("Success", "Profile updated successfully", updatedAdmin),
					HttpStatus.OK);
		} catch (SecurityUtils.SecurityIntegrityException e) {
			// Must not be swallowed by the catch-all below. It was: the tenant
			// guard aborted the update correctly, but the response came back as
			// a 500 -- indistinguishable from a server fault -- and carried
			// e.getMessage(), which reads "resource adminId=X does not match
			// authenticated adminId=Y". That bypassed the fixed message the
			// exception handler now returns.
			throw e;
		} catch (Exception e) {
			log.error("Failed to update admin profile {}", id, e);
			return new ResponseEntity<>(new RestAPIResponse("Fail", "Profile not updated", null),
					HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	@GetMapping("/settings")
	public ResponseEntity<RestAPIResponse> getSettings() {
		try {
			Long adminId = SecurityUtils.getCurrentAdminId();
			Admin settings = adminServiceImpl.getSettings(adminId);
			return new ResponseEntity<>(new RestAPIResponse("Success", "Settings retrieved", settings), HttpStatus.OK);
		} catch (Exception e) {
			return new ResponseEntity<>(new RestAPIResponse("Fail", "Settings unavailable", null), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	@PutMapping("/settings")
	public ResponseEntity<RestAPIResponse> updateSettings(@RequestBody Admin settings) {
		try {
			Long adminId = SecurityUtils.getCurrentAdminId();
			Admin updated = adminServiceImpl.updateSettings(adminId, settings);
			if (updated == null) {
				return new ResponseEntity<>(new RestAPIResponse("Fail", "Settings not found", null), HttpStatus.NOT_FOUND);
			}
			return new ResponseEntity<>(new RestAPIResponse("Success", "Settings updated", updated), HttpStatus.OK);
		} catch (Exception e) {
			return new ResponseEntity<>(new RestAPIResponse("Fail", "Settings unavailable", null), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	@DeleteMapping("/settings/reset")
	public ResponseEntity<RestAPIResponse> resetSettings() {
		try {
			Long adminId = SecurityUtils.getCurrentAdminId();
			adminServiceImpl.resetSettings(adminId);
			return new ResponseEntity<>(new RestAPIResponse("Success", "Settings reset to defaults", null), HttpStatus.OK);
		} catch (Exception e) {
			return new ResponseEntity<>(new RestAPIResponse("Fail", "Settings unavailable", null), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	@DeleteMapping("/deleted/{id}")
	public ResponseEntity<RestAPIResponse> deleteUpdatedProfile(@PathVariable Long id) {
		try {
			// Tenant-scope: caller can only delete their own admin profile
			Long currentAdminId = SecurityUtils.getCurrentAdminId();
			SecurityUtils.assertOwnedByCurrentTenant(id);

			boolean deleted = adminServiceImpl.deleteProfile(currentAdminId);
			if (!deleted) {
				return new ResponseEntity<>(new RestAPIResponse("Fail", "Profile not found with ID: " + id, null),
						HttpStatus.NOT_FOUND);
			}
			return new ResponseEntity<>(new RestAPIResponse("Success", "Profile deleted successfully", null),
					HttpStatus.OK);
		} catch (SecurityUtils.SecurityIntegrityException e) {
			throw e;
		} catch (Exception e) {
			log.error("Failed to delete admin profile {}", id, e);
			return new ResponseEntity<>(new RestAPIResponse("Fail", "Profile not deleted", null),
					HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
}
