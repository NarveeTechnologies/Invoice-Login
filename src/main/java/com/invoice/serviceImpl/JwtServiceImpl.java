package com.invoice.serviceImpl;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.invoice.entity.User;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class JwtServiceImpl {

	@Value("${jwt.secret}")
	private String secret;

	@Value("${jwt.expiration}")
	private long expiration;

	// 🔑 Single signing key source (VERY IMPORTANT)
	private Key getSigningKey() {
		return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
	}

	// ================= GENERATE TOKEN =================

	public String generateToken(User user, String roleName, Set<String> privileges) {

		Map<String, Object> claims = new HashMap<>();
		claims.put("roles", roleName != null ? List.of(roleName) : Collections.emptyList());
		claims.put("privileges", privileges != null ? privileges : Collections.emptySet());
		if (user.getCompanyDomain() != null) {
			claims.put("companyDomain", user.getCompanyDomain());
		}

		return Jwts.builder().setClaims(claims).setSubject(user.getEmail()).setIssuedAt(new Date())
				.setExpiration(new Date(System.currentTimeMillis() + expiration))
				.signWith(getSigningKey(), SignatureAlgorithm.HS256).compact();
	}

	// ================= VALIDATE =================

	public boolean validateToken(String token) {
		try {
			extractAllClaims(token); // parse once
			return true;
		} catch (JwtException | IllegalArgumentException e) {
			log.warn("Invalid JWT: {}", e.getMessage());
			return false;
		}
	}

	// ================= EXTRACT CLAIMS =================

	public Claims extractAllClaims(String token) {
		return Jwts.parserBuilder().setSigningKey(getSigningKey()).build().parseClaimsJws(token).getBody();
	}

	// ================= EXTRACT USERNAME =================

	public String extractUsername(String token) {
		return extractAllClaims(token).getSubject();
	}

	// ================= OPTIONAL: expose key =================

	public Key getSigningKeyPublic() {
		return getSigningKey();
	}
}
