package com.invoice.controller;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.invoice.DTO.PrivilegeDTO;
import com.invoice.DTO.RoleDTO;
import com.invoice.commons.RestAPIResponse;
import com.invoice.entity.Role;
import com.invoice.serviceImpl.PrivilegeServiceImpl;
import com.invoice.serviceImpl.RoleServiceImpl;
import com.invoice.utils.SanitizerUtils;

@RestController
@RequestMapping("/auth/roles")
public class RoleController {

	@Autowired
	private RoleServiceImpl roleServiceImpl;

	@Autowired
	private PrivilegeServiceImpl privilegeServiceImpl;

	private static final Logger log = LoggerFactory.getLogger(RoleController.class);

	@PostMapping(value = "/save", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<RestAPIResponse> createRole(@RequestBody RoleDTO roleDTO,
	                                                  Authentication authentication) {
	    try {
	        String loggedInEmail = authentication.getName();
	        RoleDTO saved = roleServiceImpl.createRole(roleDTO, loggedInEmail);

	        return ResponseEntity.ok(
	                new RestAPIResponse("success", "Role saved successfully", saved));

	    } catch (org.springframework.dao.DataIntegrityViolationException e) {

	        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
	                .body(new RestAPIResponse("error", 
	                        "Role already exists for this admin", null));

	    } catch (RuntimeException e) {

	        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
	                .body(new RestAPIResponse("error", e.getMessage(), null));

	    } catch (Exception e) {

	        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
	                .body(new RestAPIResponse("error", "Something went wrong", null));
	    }
	}

	// ✅ Assign privileges category-wise to a role
	@PostMapping("/privilege/save")
	public ResponseEntity<RestAPIResponse> assignPrivilegesToRole(@RequestBody Map<String, Object> payload) {
		try {
			log.info("Received payload: {}", payload);

			Object roleIdObj = payload.get("roleId");
			Object categoryObj = payload.get("category");
			Object privilegeIdsObj = payload.get("privilegeIds");

			if (roleIdObj == null)
				throw new RuntimeException("Missing field: roleId");
			if (categoryObj == null)
				throw new RuntimeException("Missing field: category");
			if (privilegeIdsObj == null)
				throw new RuntimeException("Missing field: privilegeIds");

			Long roleId = Long.parseLong(roleIdObj.toString());
			String category = categoryObj.toString();
			List<Integer> privilegeIds = (List<Integer>) privilegeIdsObj;

			Set<Long> privilegeIdSet = privilegeIds.stream().map(Integer::longValue).collect(Collectors.toSet());

			// ✅ Update privileges category-wise
			roleServiceImpl.updateRolePrivileges(roleId, privilegeIdSet, category);

			// ✅ Return refreshed privilege grouping
			Map<String, List<PrivilegeDTO>> groupedPrivileges = privilegeServiceImpl.getPrivilegesByRole(roleId);

			return ResponseEntity
					.ok(new RestAPIResponse("success", "Privileges assigned successfully", groupedPrivileges));

		} catch (Exception e) {
			log.error("Error assigning privileges: {}", e.getMessage());
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(new RestAPIResponse("error", "Failed to assign privileges: " + e.getMessage(), null));
		}
	}

	@GetMapping("/search")
	public ResponseEntity<RestAPIResponse> searchRoles(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size, @RequestParam(defaultValue = "roleId") String sortBy,
			@RequestParam(defaultValue = "asc") String sortDir, @RequestParam(required = false) String keyword) {

		Page<RoleDTO> result = roleServiceImpl.searchRoles(page, size, sortBy, sortDir,
				SanitizerUtils.sanitize(keyword));

		return ResponseEntity.ok(new RestAPIResponse("success", "Roles fetched", result));
	}

	// Get all roles
	@GetMapping("/getall")
	public ResponseEntity<RestAPIResponse> getAllRoles() {
		try {
			List<RoleDTO> roles = roleServiceImpl.getAllRoles();
			return ResponseEntity.ok(new RestAPIResponse("success", "All roles retrieved successfully", roles));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(new RestAPIResponse("error", "Failed to retrieve roles: " + e.getMessage(), null));
		}
	}

	// ✅ Get single role by ID
	@GetMapping("/{roleId}")
	public ResponseEntity<RestAPIResponse> getRoleById(@PathVariable Long roleId) {
		try {
			RoleDTO role = roleServiceImpl.getRoleById(roleId);
			return ResponseEntity.ok(new RestAPIResponse("success", "Role retrieved successfully", role));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(new RestAPIResponse("error", "Failed to retrieve role: " + e.getMessage(), null));
		}
	}

	// ✅ Get privileges assigned to a role
	@GetMapping("/{roleId}/privileges")
	public ResponseEntity<RestAPIResponse> getPrivilegesByRole(@PathVariable Long roleId) {
		try {
			Map<String, List<PrivilegeDTO>> privileges = privilegeServiceImpl.getPrivilegesByRole(roleId);
			return ResponseEntity.ok(new RestAPIResponse("success", "Fetched privileges for role", privileges));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(new RestAPIResponse("error", "Failed to fetch privileges: " + e.getMessage(), null));
		}
	}

	// ✅ Update role details
	@PutMapping("/{roleId}")
	public ResponseEntity<RestAPIResponse> updateRole(@PathVariable Long roleId, @RequestBody RoleDTO roleDTO,
			Authentication authentication) {
		try {
			String loggedInEmail = authentication.getName();
			RoleDTO updated = roleServiceImpl.updateRole(roleId, roleDTO, loggedInEmail);
			return ResponseEntity.ok(new RestAPIResponse("success", "Role updated successfully", updated));
		} catch (RuntimeException e) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new RestAPIResponse("error", e.getMessage(), null));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(new RestAPIResponse("error", "Failed to update role: " + e.getMessage(), null));
		}
	}

	// ✅ Update privileges for a role
	@PutMapping("/{roleId}/privileges")
	public ResponseEntity<RestAPIResponse> updateRolePrivileges(@PathVariable Long roleId,
			@RequestBody Map<String, Object> payload) {

		try {
			// Extract category and privilege IDs from JSON
			String category = payload.get("category").toString();
			List<Integer> privilegeIds = (List<Integer>) payload.get("privilegeIds");

			Set<Long> privilegeIdSet = privilegeIds.stream().map(Integer::longValue).collect(Collectors.toSet());

			// Call category-aware update
			RoleDTO updated = roleServiceImpl.updateRolePrivileges(roleId, privilegeIdSet, category);

			return ResponseEntity.ok(new RestAPIResponse("success", "Privileges updated successfully", updated));
		} catch (Exception e) {
			log.error(" Failed to update privileges for role {}: {}", roleId, e.getMessage());
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(new RestAPIResponse("error", "Failed to update privileges: " + e.getMessage(), null));
		}
	}

	// Delete role
	@DeleteMapping("/{roleId}")
	public ResponseEntity<RestAPIResponse> deleteRole(@PathVariable Long roleId) {
		try {
			roleServiceImpl.deleteRole(roleId);
			return ResponseEntity.ok(new RestAPIResponse("success", "Role deleted successfully", null));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(new RestAPIResponse("error", "Failed to delete role: " + e.getMessage(), null));
		}
	}
	
	@GetMapping("/roles/{adminId}")
	public ResponseEntity<RestAPIResponse> getRolesByAdminId(@PathVariable Long adminId) {

	    try {

	        List<RoleDTO> roles = roleServiceImpl.getRolesByAdminId(adminId);

	        return ResponseEntity.ok(
	                new RestAPIResponse("success", "Roles retrieved successfully", roles));

	    } catch (Exception e) {

	        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
	                .body(new RestAPIResponse("error", e.getMessage(), null));
	    }
	}
	
	
	@GetMapping("/adminId/search")
	public ResponseEntity<RestAPIResponse> searchRoles(
	        @RequestParam(defaultValue = "0") int page,
	        @RequestParam(defaultValue = "10") int size,
	        @RequestParam(defaultValue = "roleId") String sortBy,
	        @RequestParam(defaultValue = "asc") String sortDir,
	        @RequestParam(required = false) String keyword,
	        Authentication authentication) {

	    String loggedInEmail = authentication.getName();

	    Page<RoleDTO> result = roleServiceImpl.searchRoles(
	            page,
	            size,
	            sortBy,
	            sortDir,
	            SanitizerUtils.sanitize(keyword),
	            loggedInEmail
	    );

	    return ResponseEntity.ok(
	            new RestAPIResponse("success", "Roles fetched successfully", result)
	    );
	}
}
