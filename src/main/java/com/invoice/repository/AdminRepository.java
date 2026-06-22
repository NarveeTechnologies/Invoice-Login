package com.invoice.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.invoice.entity.Admin;

@Repository
public interface AdminRepository extends JpaRepository<Admin, Long> {

	Optional<Admin> findByPrimaryEmail(String primaryEmail);

	@org.springframework.data.jpa.repository.Query("SELECT a FROM Admin a WHERE LOWER(a.primaryEmail) = LOWER(:email)")
	Optional<Admin> findByEmailIgnoreCase(@org.springframework.data.repository.query.Param("email") String email);
}
