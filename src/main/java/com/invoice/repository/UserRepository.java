package com.invoice.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.invoice.entity.ManageUsers;
import com.invoice.entity.User;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
	Optional<User> findByEmailIgnoreCase(String email);

	boolean existsByEmailIgnoreCase(String email);

	Optional<User> findByEmail(String email)

	;

	@Modifying
	@Query("UPDATE User u SET u.fullName = :fullName WHERE u.id = :userId")
	void updateFullName(@Param("userId") Long userId, @Param("fullName") String fullName);

	@Query("SELECT COUNT(u) FROM User u WHERE u.role.roleId = :roleId")
	long countByRoleId(@Param("roleId") Long roleId);

	// Count how many users have a given role
	long countByRole_RoleId(Long roleId);

	@Query("SELECT u FROM User u LEFT JOIN FETCH u.createdBy WHERE LOWER(u.email) = LOWER(:email)")
	Optional<User> findByEmailIgnoreCaseWithCreator(@Param("email") String email);

	// (Optional) Find users with that role if you ever want to nullify before
	// delete
	List<User> findByRole_RoleId(Long roleId);
	
	public boolean existsByEmail(String email);


}
