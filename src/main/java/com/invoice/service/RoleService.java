package com.invoice.service;

import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;

import com.invoice.DTO.RoleDTO;

public interface RoleService {

	public RoleDTO createRole(RoleDTO roleDTO, String loggedInEmail);

	RoleDTO updateRole(Long roleId, RoleDTO roleDTO, String loggedInEmail);

	void deleteRole(Long roleId, String loggedInEmail);

	public Page<RoleDTO> searchRoles(int page, int size, String sortBy, String sortDir, String keyword,
			String loggedInEmail);

	RoleDTO assignPrivilegeToRole(Long roleId, Long privilegeId, Long adminId);

	List<RoleDTO> getAllRoles(String loggedInEmail);

	RoleDTO getRoleById(Long id, String loggedInEmail);

	// ✅ Updated signature
	RoleDTO updateRolePrivileges(Long roleId, Set<Long> selectedPrivilegeIds, String category,
			String loggedInEmail);

	public List<RoleDTO> getRolesByAdminId(Long adminId);

}
