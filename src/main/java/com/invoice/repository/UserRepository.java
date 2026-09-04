package com.invoice.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
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

	/**
	 * Loads a user with everything that gets serialised with it.
	 *
	 * <p>{@code /auth/me} returns the {@link com.invoice.entity.User} entity
	 * itself, so Jackson walks its associations — and it does that after the
	 * controller has returned, when no persistence context is open. Without this
	 * graph, {@code bankDetails} and {@code role.privileges} both throw
	 * {@code LazyInitializationException} the moment
	 * {@code spring.jpa.open-in-view} is disabled.
	 *
	 * <p>{@code createdBy} is deliberately absent: it is {@code @JsonIgnore} on
	 * the entity, because no graph can terminate a self-referential association
	 * that Jackson walks. See {@link com.invoice.entity.User#getCreatedBy()}.
	 *
	 * <p>{@code role.privileges} is in the graph because {@code role} is EAGER
	 * but its privileges are not: Jackson walks straight through the eagerly
	 * loaded Role into a lazy collection. An association being eager says
	 * nothing about what hangs off it.
	 *
	 * <p>A separate finder rather than a graph on
	 * {@link #findByEmailIgnoreCase}: that one is on the login path, which has
	 * no interest in bank details, and adding a collection join to it would make
	 * every sign-in pay for data it discards.
	 */
	@EntityGraph(attributePaths = { "bankDetails", "role", "role.privileges" })
	Optional<User> findWithProfileByEmailIgnoreCase(String email);

	/** As {@link #findWithProfileByEmailIgnoreCase}, by id. */
	@EntityGraph(attributePaths = { "bankDetails", "role", "role.privileges" })
	Optional<User> findWithProfileById(Long id);

	@Query("SELECT u FROM User u LEFT JOIN FETCH u.createdBy WHERE LOWER(u.email) = LOWER(:email)")
	Optional<User> findByEmailIgnoreCaseWithCreator(@Param("email") String email);

	// (Optional) Find users with that role if you ever want to nullify before
	// delete
	List<User> findByRole_RoleId(Long roleId);

	public boolean existsByEmail(String email);

}
