package com.invoice.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.invoice.entity.OTP;
import com.invoice.entity.Role;

import jakarta.transaction.Transactional;

@Repository
public interface TokenRepository extends JpaRepository<OTP, Long> {
	Optional<OTP> findByEmailAndOtp(String email, String otp);

	@Modifying
	@Transactional
	@Query("DELETE FROM OTP t WHERE t.email = :email")
	void deleteByEmail(@Param("email") String email);

	Optional<OTP> findByEmail(String email);

}
