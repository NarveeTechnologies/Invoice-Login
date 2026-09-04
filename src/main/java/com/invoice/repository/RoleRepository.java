package com.invoice.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.invoice.entity.Role;

public interface RoleRepository extends JpaRepository<Role, Long> {

	Optional<Role> findByRoleName(String roleName);

	Optional<Role> findByRoleNameIgnoreCase(String roleName);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE Role r SET r.addedByName = :fullName WHERE r.addedBy = :userId")
	void updateAddedByName(Long userId, String fullName);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE Role r SET r.updatedByName = :fullName WHERE r.updatedBy = :userId")
	void updateUpdatedByName(Long userId, String fullName);

	/**
	 * All roles with their privileges, in one query.
	 *
	 * <p>{@code convertToDTO} reads {@code role.getPrivileges()} for every row.
	 * Making the service method transactional stopped that throwing, but it
	 * replaced the exception with an N+1: measured at 22 queries for 21 roles,
	 * one per role. A transaction makes lazy loading work, which is precisely
	 * why the cost stops being visible.
	 */
	@EntityGraph(attributePaths = "privileges")
	@Query("SELECT DISTINCT r FROM Role r")
	List<Role> findAllWithPrivileges();

	/** As {@link #findAllWithPrivileges}, scoped to one admin. */
	@EntityGraph(attributePaths = "privileges")
	@Query("SELECT DISTINCT r FROM Role r WHERE r.adminId = :adminId")
	List<Role> findByAdminIdWithPrivileges(@Param("adminId") Long adminId);

	@Query("SELECT r FROM Role r LEFT JOIN FETCH r.privileges WHERE r.roleId = :roleId")
	Optional<Role> findByIdWithPrivileges(@Param("roleId") Long roleId);

	@Query("SELECT m.roleName FROM ManageUsers m WHERE m.id = :id")
	String getRoleNameById(@Param("id") Long id);

	@Query("SELECT r\r\n" + "    	    FROM Role r\r\n" + "    	    WHERE\r\n"
			+ "    	        (:keyword IS NULL OR :keyword = '' OR\r\n"
			+ "    	         LOWER(r.roleName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR\r\n"
			+ "    	         LOWER(r.description) LIKE LOWER(CONCAT('%', :keyword, '%')) OR\r\n"
			+ "    	         LOWER(r.status) LIKE LOWER(CONCAT('%', :keyword, '%')) OR\r\n"
			+ "    	         LOWER(r.addedByName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR\r\n"
			+ "    	         LOWER(r.updatedByName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR\r\n"
			+ "    	         CAST(r.roleId AS string) LIKE CONCAT('%', :keyword, '%') OR\r\n"
			+ "    	         CAST(r.addedBy AS string) LIKE CONCAT('%', :keyword, '%') OR\r\n"
			+ "    	         CAST(r.updatedBy AS string) LIKE CONCAT('%', :keyword, '%')\r\n" + "    	        )")
	Page<Role> searchAll(@Param("keyword") String keyword, Pageable pageable);

	/**
	 * Paged search confined to one tenant.
	 *
	 * <p>The unscoped {@link #searchAll} and {@code findAll(Pageable)} behind
	 * {@code GET /auth/roles/search} returned every tenant's roles, with their
	 * privilege names. Found by probing the response body — the endpoint answers
	 * 200 either way.
	 */
	@Query("""
			SELECT r FROM Role r
			 WHERE r.adminId = :adminId
			   AND (:keyword IS NULL OR :keyword = ''
			        OR LOWER(r.roleName) LIKE LOWER(CONCAT('%', :keyword, '%'))
			        OR LOWER(r.description) LIKE LOWER(CONCAT('%', :keyword, '%'))
			        OR LOWER(r.status) LIKE LOWER(CONCAT('%', :keyword, '%')))
			""")
	Page<Role> searchWithinTenant(@Param("keyword") String keyword,
			@Param("adminId") Long adminId, Pageable pageable);

	Optional<Role> findByRoleNameIgnoreCaseAndAdminId(String roleName, Long adminId);

	List<Role> findAllByRoleNameIgnoreCase(String roleName);

	public List<Role> findByAdminId(Long adminId);

	Page<Role> findByAdminId(Long adminId, Pageable pageable);

	@Query(" SELECT r\r\n" + "		       FROM Role r\r\n" + "		       WHERE r.adminId = :adminId\r\n"
			+ "		       AND (\r\n"
			+ "		            LOWER(r.roleName) LIKE LOWER(CONCAT('%', :keyword, '%'))\r\n"
			+ "		            OR LOWER(r.addedByName) LIKE LOWER(CONCAT('%', :keyword, '%'))\r\n"
			+ "		            OR LOWER(r.updatedByName) LIKE LOWER(CONCAT('%', :keyword, '%'))\r\n"
			+ "		       )")
	Page<Role> searchByAdminId(@Param("adminId") Long adminId, @Param("keyword") String keyword, Pageable pageable);

}
