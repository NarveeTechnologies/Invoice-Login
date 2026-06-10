package com.invoice.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.invoice.entity.ManageUsers;
import com.invoice.entity.Role;
import com.invoice.entity.User;

import jakarta.transaction.Transactional;

@Repository
public interface ManageUserRepository extends JpaRepository<ManageUsers, Long>, JpaSpecificationExecutor<ManageUsers> {

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("\r\n"
			+ "			    UPDATE ManageUsers m\r\n"
			+ "			    SET m.addedByName = :fullName\r\n"
			+ "			    WHERE m.addedBy.id = :userId")
	void updateAddedByName(Long userId, String fullName);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("  UPDATE ManageUsers m\r\n"
			+ "			    SET m.updatedByName = :fullName\r\n"
			+ "			    WHERE m.updatedBy = :userId")
	void updateUpdatedByName(Long userId, String fullName);

	// === Existing methods ===
	boolean existsByEmail(String email);

	Optional<ManageUsers> findByEmailIgnoreCase(String email);

	List<ManageUsers> findByCreatedBy(User createdBy);

	boolean existsByEmailIgnoreCase(String email);

	// Same first+last name within the same company (adminId scope), case-insensitive.
	boolean existsByFirstNameIgnoreCaseAndLastNameIgnoreCaseAndAdminId(String firstName, String lastName,
			Long adminId);

	// Same as above but excluding a given user id (for the update/edit path). Using a
	// boolean exists-query (not findBy...Optional) is important: if duplicate names
	// already exist in the data, an Optional finder throws NonUniqueResultException.
	boolean existsByFirstNameIgnoreCaseAndLastNameIgnoreCaseAndAdminIdAndIdNot(String firstName, String lastName,
			Long adminId, Long id);

	List<ManageUsers> findByAddedBy(User addedBy);

	List<ManageUsers> findByAddedBy_Id(Long addedById);

	ManageUsers findByEmail(String email);

	@Query("SELECT COUNT(u) FROM User u WHERE u.role.roleId = :roleId")
	long countUsersWithRole(@Param("roleId") Long roleId);

	// === Company-based filters ===
//    List<ManageUsers> findByCompanyId(Long companyId);
//    Page<ManageUsers> findByCompanyId(Long companyId, Pageable pageable);

//    @Query("""
//        SELECT m FROM ManageUsers m 
//        WHERE m.companyId = :companyId
//        AND (
//            LOWER(m.firstName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR 
//            LOWER(m.middleName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR 
//            LOWER(m.lastName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR 
//            LOWER(m.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR 
//            LOWER(m.roleName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR 
//            LOWER(m.updatedByName) LIKE LOWER(CONCAT('%', :keyword, '%'))
//        )
//    """)
//    Page<ManageUsers> searchByCompany(@Param("keyword") String keyword,
//                                      @Param("companyId") Long companyId,
//                                      Pageable pageable);

	// === Global search (for SUPERADMIN) ===
	@Query("SELECT m FROM ManageUsers m\r\n"
			+ "			    WHERE LOWER(m.firstName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR\r\n"
			+ "			          LOWER(m.middleName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR\r\n"
			+ "			          LOWER(m.lastName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR\r\n"
			+ "			          LOWER(m.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR\r\n"
			+ "			          LOWER(m.role.roleName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR\r\n"
			+ "			          LOWER(m.updatedByName) LIKE LOWER(CONCAT('%', :keyword, '%'))")
	Page<ManageUsers> search(@Param("keyword") String keyword, Pageable pageable);

	@Query(" SELECT u FROM ManageUsers u\r\n"
			+ "			    LEFT JOIN FETCH u.role r\r\n"
			+ "			    WHERE\r\n"
			+ "			        (:keyword IS NULL OR :keyword = '' OR\r\n"
			+ "			         LOWER(u.firstName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR\r\n"
			+ "			         LOWER(u.middleName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR\r\n"
			+ "			         LOWER(u.lastName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR\r\n"
			+ "			         LOWER(u.fullName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR\r\n"
			+ "			         LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR\r\n"
			+ "			         LOWER(u.primaryEmail) LIKE LOWER(CONCAT('%', :keyword, '%')) OR\r\n"
			+ "			         LOWER(r.roleName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR\r\n"
			+ "			         LOWER(u.addedByName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR\r\n"
			+ "			         LOWER(u.updatedByName) LIKE LOWER(CONCAT('%', :keyword, '%'))\r\n"
			+ "			        )")
	Page<ManageUsers> searchUsers(@Param("keyword") String keyword, Pageable pageable);

	boolean existsByCompanyDomainAndRoleNameIgnoreCase(String companyDomain, String roleName);

	Optional<ManageUsers> findFirstByCompanyDomainIgnoreCase(String companyDomain);

	boolean existsByCompanyDomainAndRole_RoleNameIgnoreCase(String companyDomain, String roleName);

	boolean existsByCompanyDomainAndRole(String domain, Role adminRole);
	
	List<ManageUsers> findByCompanyDomainIgnoreCase(String companyDomain);
	
    @Query("SELECT m.roleName FROM ManageUsers m WHERE m.id = :id")
    String getRoleNameById(@Param("id") Long id);

    // ✅ For sorting without keyword - ALL USERS (for SUPERADMIN)
    Page<ManageUsers> findAll(Pageable pageable);
    
    // ✅ For sorting without keyword - FILTERED BY DOMAIN (for ADMIN)
    @Query("SELECT m FROM ManageUsers m WHERE LOWER(m.companyDomain) = LOWER(:domain)")
    Page<ManageUsers> getAllManageUsersByDomain(@Param("domain") String domain, Pageable pageable);
    
    // ✅ For searching with keyword - ALL USERS (for SUPERADMIN)
    @Query("SELECT m FROM ManageUsers m WHERE " +
           "LOWER(COALESCE(m.firstName, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(COALESCE(m.middleName, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(COALESCE(m.lastName, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(COALESCE(m.fullName, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(COALESCE(m.email, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(COALESCE(m.roleName, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(COALESCE(m.companyName, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(COALESCE(m.mobileNumber, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(COALESCE(m.addedByName, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    Page<ManageUsers> searchManageUsers(@Param("keyword") String keyword, Pageable pageable);
    
    // ✅ For searching with keyword - FILTERED BY DOMAIN (for ADMIN)
    @Query("SELECT m FROM ManageUsers m WHERE LOWER(m.companyDomain) = LOWER(:domain) AND (" +
           "LOWER(COALESCE(m.firstName, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(COALESCE(m.middleName, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(COALESCE(m.lastName, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(COALESCE(m.fullName, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(COALESCE(m.email, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(COALESCE(m.roleName, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(COALESCE(m.companyName, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(COALESCE(m.mobileNumber, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(COALESCE(m.addedByName, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<ManageUsers> searchManageUsersByDomain(@Param("keyword") String keyword, 
                                                 @Param("domain") String domain, 
                                                 Pageable pageable);
    
    // ✅ For getting single user's data (for regular users)
    @Query("SELECT m FROM ManageUsers m WHERE LOWER(m.email) = LOWER(:email)")
    Page<ManageUsers> getManageUserByEmail(@Param("email") String email, Pageable pageable);

    
    List<ManageUsers> findByCreatedBy_Id(Long createdById);



}
