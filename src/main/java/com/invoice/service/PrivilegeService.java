package com.invoice.service;

import java.util.List;
import java.util.Map;

import com.invoice.DTO.PrivilegeDTO;

public interface PrivilegeService {

	// Basic CRUD
	PrivilegeDTO createPrivilege(PrivilegeDTO privilegeDTO);

	PrivilegeDTO updatePrivilege(Long id, PrivilegeDTO privilegeDTO);

	// Fetch operations
	List<PrivilegeDTO> getAllPrivileges();

	PrivilegeDTO getPrivilegeById(Long id);

	List<PrivilegeDTO> getPrivilegesByCategory(String category);

	Map<String, List<PrivilegeDTO>> getAllPrivilegesGrouped();

	Map<String, List<PrivilegeDTO>> getPrivilegesByRole(Long roleId);

	Map<String, String> getEndpointPrivilegesMap();

	public void deletePrivilegesByCategoryId(Long categoryId);

	public void deletePrivilege(Long id);
}
