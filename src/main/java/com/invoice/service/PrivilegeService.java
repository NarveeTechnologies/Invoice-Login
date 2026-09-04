package com.invoice.service;

import java.util.List;
import java.util.Map;

import com.invoice.DTO.PrivilegeDTO;

public interface PrivilegeService {

	// Basic CRUD
	PrivilegeDTO createPrivilege(PrivilegeDTO privilegeDTO, String loggedInEmail);

	PrivilegeDTO updatePrivilege(Long id, PrivilegeDTO privilegeDTO, String loggedInEmail);

	// Fetch operations
	List<PrivilegeDTO> getAllPrivileges();

	PrivilegeDTO getPrivilegeById(Long id, String loggedInEmail);

	List<PrivilegeDTO> getPrivilegesByCategory(String category);

	Map<String, List<PrivilegeDTO>> getAllPrivilegesGrouped(String loggedInEmail);

	Map<String, List<PrivilegeDTO>> getPrivilegesByRole(Long roleId, String loggedInEmail);

	Map<String, String> getEndpointPrivilegesMap();

	public void deletePrivilegesByCategoryId(Long categoryId, String loggedInEmail);

	public void deletePrivilege(Long id, String loggedInEmail);
}
