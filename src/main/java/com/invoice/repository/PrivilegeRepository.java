package com.invoice.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.invoice.entity.Privilege;
import com.invoice.entity.Role;

import jakarta.persistence.QueryHint;

public interface PrivilegeRepository extends JpaRepository<Privilege, Long> {

	Privilege findByName(String name);

	Set<Privilege> findByCategory(String category);

	List<Privilege> getPrivilegesByCategory(String category);

	// Fetch privileges by category (case-insensitive)
	List<Privilege> findByCategoryIgnoreCase(String category);

	// Optional: fetch by name (if needed)
	Optional<Privilege> findByNameIgnoreCase(String name);

	// Step 1: Find all privilege IDs by category (string)
	@Query("SELECT p.id FROM Privilege p WHERE p.category = :category")
	List<Long> findIdsByCategory(@Param("category") String category);

	// Step 2: Delete all privileges by category (string)
	@Modifying
	@Query("DELETE FROM Privilege p WHERE p.category = :category")
	void deleteByCategory(@Param("category") String category);

	
	/**
	 * Privileges visible to one tenant: its own, plus the shared platform
	 * definitions.
	 *
	 * <p>{@code adminId IS NULL} is deliberately included — {@code createPrivilege}
	 * does not set an owner, so the shared catalogue has a null tenant. Scoping
	 * strictly to the caller's tenant would hide it and empty the privileges
	 * screen; not scoping at all exposed other tenants' custom privileges, which
	 * is what {@code GET /auth/privileges/getall} was doing.
	 */
	@Query("SELECT p FROM Privilege p WHERE p.adminId IS NULL OR p.adminId = :adminId")
	List<Privilege> findVisibleToTenant(@Param("adminId") Long adminId);

	@Query("SELECT p FROM Privilege p JOIN p.roles r WHERE r.roleId = :roleId")
	List<Privilege> findByRoles_RoleId(@Param("roleId") Long roleId);
	
//	@Query("SELECT r FROM Role r LEFT JOIN FETCH r.privileges WHERE r.roleId = :roleId")
//	Optional<Role> findByIdWithPrivileges(@Param("roleId") Long roleId);

	@QueryHints(@QueryHint(name = org.hibernate.annotations.QueryHints.CACHEABLE, value = "false"))
	@Query("SELECT p FROM Privilege p")
	List<Privilege> findAllPrivilegesFresh();
	
	
	
}
