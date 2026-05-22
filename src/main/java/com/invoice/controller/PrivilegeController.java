package com.invoice.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.invoice.DTO.PrivilegeDTO;
import com.invoice.commons.RestAPIResponse;
import com.invoice.serviceImpl.PrivilegeServiceImpl;

import jakarta.persistence.EntityNotFoundException;

@RestController
@RequestMapping("/auth/privileges")
public class PrivilegeController {

	@Autowired
	private PrivilegeServiceImpl privilegeServiceImpl;

	// ✅ Create Privilege (using DTO)
	@PostMapping("/save")
	public ResponseEntity<RestAPIResponse> createPrivilege(@RequestBody PrivilegeDTO privilegeDTO) {
		try {
			PrivilegeDTO saved = privilegeServiceImpl.createPrivilege(privilegeDTO);
			return ResponseEntity.ok(new RestAPIResponse("success", "Privilege saved successfully", saved));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(new RestAPIResponse("error", "Failed to save privilege: " + e.getMessage(), null));
		}
	}

	// ✅ Get all Privileges (grouped by category)
	@GetMapping("/getall")
	public ResponseEntity<RestAPIResponse> getAllPrivileges() {
		try {
			Map<String, List<PrivilegeDTO>> groupedPrivileges = privilegeServiceImpl.getAllPrivilegesGrouped();
			return ResponseEntity
					.ok(new RestAPIResponse("success", "Successfully fetched all privileges", groupedPrivileges));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(new RestAPIResponse("error", "Failed to fetch all privileges: " + e.getMessage(), null));
		}
	}

	// ✅ Get privileges assigned to a specific role
	@GetMapping("/role/{roleId}")
	public ResponseEntity<RestAPIResponse> getPrivilegesByRole(@PathVariable Long roleId) {
		try {
			Map<String, List<PrivilegeDTO>> privileges = privilegeServiceImpl.getPrivilegesByRole(roleId);
			return ResponseEntity
					.ok(new RestAPIResponse("success", "Fetched privileges by role successfully", privileges));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(new RestAPIResponse("error", "Failed to fetch privileges by role: " + e.getMessage(), null));
		}
	}

	// ✅ Get privilege by ID
	@GetMapping("/{id}")
	public ResponseEntity<RestAPIResponse> getPrivilegeById(@PathVariable Long id) {
		try {
			PrivilegeDTO privilege = privilegeServiceImpl.getPrivilegeById(id);
			return ResponseEntity.ok(new RestAPIResponse("success", "Privilege fetched successfully", privilege));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(new RestAPIResponse("error", "Failed to fetch privilege: " + e.getMessage(), null));
		}
	}

	// ✅ Endpoint Privilege Mapping (static or DB-based)
	@GetMapping("/access/endpoint-privileges")
	public ResponseEntity<RestAPIResponse> getEndpointPrivileges() {
		try {
			Map<String, String> map = privilegeServiceImpl.getEndpointPrivilegesMap();
			return ResponseEntity.ok(new RestAPIResponse("success", "Fetched endpoint privileges", map));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(new RestAPIResponse("error", "Failed to fetch endpoint privileges: " + e.getMessage(), null));
		}
	}

	// ✅ Update Privilege
	@PutMapping("/{id}")
	public ResponseEntity<RestAPIResponse> updatePrivilege(@PathVariable Long id,
			@RequestBody PrivilegeDTO privilegeDTO) {
		try {
			PrivilegeDTO updated = privilegeServiceImpl.updatePrivilege(id, privilegeDTO);
			return ResponseEntity.ok(new RestAPIResponse("success", "Privilege updated successfully", updated));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(new RestAPIResponse("error", "Failed to update privilege: " + e.getMessage(), null));
		}
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("hasAnyAuthority('ADMIN', 'SUPERADMIN')")
	public ResponseEntity<RestAPIResponse> deletePrivilegesByCategoryId(@PathVariable Long id) {
		try {
			privilegeServiceImpl.deletePrivilegesByCategoryId(id);

			return ResponseEntity.ok(new RestAPIResponse("success",
					"All privileges under the same category deleted successfully", null));

		} catch (EntityNotFoundException e) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(new RestAPIResponse("error", "Privilege not found with ID: " + id, null));

		} catch (DataIntegrityViolationException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(new RestAPIResponse("error",
					"Cannot delete privileges due to linked records: " + e.getMostSpecificCause().getMessage(), null));

		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest()
					.body(new RestAPIResponse("error", "Invalid privilege ID: " + e.getMessage(), null));

		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new RestAPIResponse("error",
					"Failed to delete privileges for privilege ID " + id + ": " + e.getMessage(), null));
		}
	}

}