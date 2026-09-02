package com.invoice.utils;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;

@Component
public class JwtUtil {

	@Value("${jwt.secret}")
	private String secret;

	@Value("${jwt.expiration:86400000}") // 24 hours in milliseconds
	private Long expiration;

	@Value("${jwt.issuer}")
	private String issuer;

	@Value("${jwt.audience}")
	private String audience;

	@Value("${jwt.clock-skew-seconds:30}")
	private long clockSkewSeconds;

	@jakarta.annotation.PostConstruct
	void assertSigningKeyIsStrong() {
		if (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException(
					"jwt.secret must be at least 32 bytes (256 bits) for HS256. "
							+ "Supply it via JWT_SECRET; there is no default.");
		}
	}

	private Key getSigningKey() {
		return Keys.hmacShaKeyFor(secret.getBytes());
	}

	/**
	 * Generate JWT token
	 */
	public String generateToken(String email, String role) {
		Date now = new Date();
		Date expiryDate = new Date(now.getTime() + expiration);

		return Jwts.builder().setSubject(email).claim("role", role)
				.setIssuer(issuer)
				.setAudience(audience)
				.setIssuedAt(now)
				.setExpiration(expiryDate)
				.signWith(getSigningKey(), SignatureAlgorithm.HS256)
				.compact();
	}

	/**
	 * Extract email from token
	 */
	public String getEmailFromToken(String token) {
		Claims claims = Jwts.parserBuilder().setSigningKey(getSigningKey())
				.requireIssuer(issuer)
				.requireAudience(audience)
				.setAllowedClockSkewSeconds(clockSkewSeconds)
				.build().parseClaimsJws(token).getBody();

		return claims.getSubject();
	}

	/**
	 * Extract role from token
	 */
	public String getRoleFromToken(String token) {
		Claims claims = Jwts.parserBuilder().setSigningKey(getSigningKey())
				.requireIssuer(issuer)
				.requireAudience(audience)
				.setAllowedClockSkewSeconds(clockSkewSeconds)
				.build().parseClaimsJws(token).getBody();

		return claims.get("role", String.class);
	}

	/**
	 * Validate token
	 */
	public boolean validateToken(String token) {
		try {
			Jwts.parserBuilder().setSigningKey(getSigningKey())
				.requireIssuer(issuer)
				.requireAudience(audience)
				.setAllowedClockSkewSeconds(clockSkewSeconds)
				.build().parseClaimsJws(token);
			return true;
		} catch (JwtException | IllegalArgumentException e) {
			return false;
		}
	}

	/**
	 * Check if token is expired
	 */
	public boolean isTokenExpired(String token) {
		try {
			Claims claims = Jwts.parserBuilder().setSigningKey(getSigningKey())
				.requireIssuer(issuer)
				.requireAudience(audience)
				.setAllowedClockSkewSeconds(clockSkewSeconds)
				.build().parseClaimsJws(token).getBody();

			return claims.getExpiration().before(new Date());
		} catch (JwtException e) {
			return true;
		}
	}

}