package com.invoice.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.invoice.commons.RestAPIResponse;
import com.invoice.entity.Admin;
import com.invoice.entity.User;
import com.invoice.serviceImpl.AdminServiceImpl;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/auth")
public class AdminController {

	@Autowired
	private AdminServiceImpl adminServiceImpl;

	@PostMapping("/updated/save")
	public ResponseEntity<RestAPIResponse> saveUpdatedProfile(@RequestBody Admin admin) {
		try {
			Admin savedAdmin = adminServiceImpl.saveProfile(admin);
			return new ResponseEntity<>(new RestAPIResponse("Success", "Profile saved successfully", savedAdmin),
					HttpStatus.OK);
		} catch (Exception e) {
			return new ResponseEntity<>(new RestAPIResponse("Fail", "Profile not saved: " + e.getMessage(), null),
					HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	@GetMapping("/updated/getall")
	public ResponseEntity<RestAPIResponse> getAll() {
		try {
			return new ResponseEntity<>(
					new RestAPIResponse("Success", "All profiles retrieved successfully", adminServiceImpl.getAll()),
					HttpStatus.OK);
		} catch (Exception e) {
			return new ResponseEntity<>(new RestAPIResponse("Fail", "No profiles found: " + e.getMessage(), null),
					HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	@GetMapping("/updated/{id}")
	public ResponseEntity<RestAPIResponse> getProfile(@PathVariable Long id) {

		User admin = adminServiceImpl.getById(id);
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
			Admin updatedAdmin = adminServiceImpl.updateProfile(id, admin);
			log.error("{}", admin);
			if (updatedAdmin == null) {
				log.error("{}", updatedAdmin);
				return new ResponseEntity<>(new RestAPIResponse("Fail", "Profile not found with ID: " + id, null),
						HttpStatus.NOT_FOUND);
			}
			return new ResponseEntity<>(new RestAPIResponse("Success", "Profile updated successfully", updatedAdmin),
					HttpStatus.OK);
		} catch (Exception e) {
			return new ResponseEntity<>(new RestAPIResponse("Fail", "Profile not updated: " + e.getMessage(), null),
					HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	@DeleteMapping("/deleted/{id}")
	public ResponseEntity<RestAPIResponse> deleteUpdatedProfile(@PathVariable Long id) {
		try {
			boolean deleted = adminServiceImpl.deleteProfile(id);
			if (!deleted) {
				return new ResponseEntity<>(new RestAPIResponse("Fail", "Profile not found with ID: " + id, null),
						HttpStatus.NOT_FOUND);
			}
			return new ResponseEntity<>(new RestAPIResponse("Success", "Profile deleted successfully", null),
					HttpStatus.OK);
		} catch (Exception e) {
			return new ResponseEntity<>(new RestAPIResponse("Fail", "Profile not deleted: " + e.getMessage(), null),
					HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
}
