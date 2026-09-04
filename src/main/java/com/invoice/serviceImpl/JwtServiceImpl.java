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

    /** Expected token issuer. Not a secret; verified on every request. */
    @Value("${jwt.issuer}")
    private String jwtIssuer;

    /** Expected token audience. Not a secret; verified on every request. */
    @Value("${jwt.audience}")
    private String jwtAudience;

    /** Bounded tolerance for clock drift between issuer and verifier. */
    @Value("${jwt.clock-skew-seconds:30}")
    private long jwtClockSkewSeconds;


	@Value("${jwt.expiration}")
	private long expiration;

	/** Identifies the issuing service. Verified by every consumer. Not a secret. */
	@Value("${jwt.issuer}")
	private String issuer;

	/** Identifies the intended consumer set. Verified by every consumer. Not a secret. */
	@Value("${jwt.audience}")
	private String audience;

	/**
	 * Fail closed at startup rather than issuing tokens under a weak or absent key.
	 * HS256 requires >= 256 bits of key material; a shorter secret silently weakens
	 * every token the platform issues.
	 */
	@jakarta.annotation.PostConstruct
	void assertSigningKeyIsStrong() {
		if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException(
					"jwt.secret must be at least 32 bytes (256 bits) for HS256. "
							+ "Supply it via the JWT_SECRET environment variable; there is no default.");
		}
	}

	// 🔑 Single signing key source (VERY IMPORTANT)
	private Key getSigningKey() {
		return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
	}

	// ================= GENERATE TOKEN =================

	/**
	 * Tenant-aware token generation. The adminId claim is the authoritative tenant
	 * boundary downstream services enforce; it MUST be derived from the registered
	 * ManageUsers row, never from client input.
	 */
	public String generateToken(User user, Long adminId, String roleName, Set<String> privileges) {

		if (adminId == null) {
			throw new IllegalStateException(
					"Cannot issue JWT without adminId — tenant boundary is undefined for user " + user.getEmail());
		}

		Map<String, Object> claims = new HashMap<>();
		claims.put("adminId", adminId);
		claims.put("userId", user.getId());
		claims.put("roles", roleName != null ? List.of(roleName) : Collections.emptyList());
		claims.put("privileges", privileges != null ? privileges : Collections.emptySet());
		if (user.getCompanyDomain() != null) {
			claims.put("companyDomain", user.getCompanyDomain());
		}

		return Jwts.builder().setClaims(claims).setSubject(user.getEmail())
				.setIssuer(issuer)
				.setAudience(audience)
				.setIssuedAt(new Date())
				.setExpiration(new Date(System.currentTimeMillis() + expiration))
				.signWith(getSigningKey(), SignatureAlgorithm.HS256).compact();
	}

	/**
	 * Backward-compatible overload kept for older call paths. Prefer the adminId-aware
	 * variant — this one will refuse to issue a token if the user has no tenant context.
	 */
	@Deprecated
	public String generateToken(User user, String roleName, Set<String> privileges) {
		throw new IllegalStateException(
				"generateToken without adminId is no longer supported. Callers must supply the authoritative adminId.");
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
		return Jwts.parserBuilder().setSigningKey(getSigningKey())
				.requireIssuer(jwtIssuer)
				.requireAudience(jwtAudience)
				.setAllowedClockSkewSeconds(jwtClockSkewSeconds)
				.build().parseClaimsJws(token).getBody();
	}

	// ================= EXTRACT USERNAME =================

	/**
	 * The authoritative tenant claim. Written by
	 * {@link #generateToken(com.invoice.entity.User, Long, String, java.util.Set)},
	 * which refuses to issue a token without it, so any valid token has one.
	 */
	public Long extractAdminId(String token) {
		Object claim = extractAllClaims(token).get("adminId");
		if (claim instanceof Number number) {
			return number.longValue();
		}
		return claim == null ? null : Long.valueOf(claim.toString());
	}

	public String extractUsername(String token) {
		return extractAllClaims(token).getSubject();
	}

	// ================= OPTIONAL: expose key =================

	public Key getSigningKeyPublic() {
		return getSigningKey();
	}

	/**
	 * jjwt treats requireIssuer(null)/requireAudience(null) as "no requirement" and
	 * silently accepts the token — verified empirically against jjwt 0.11.5. A blank
	 * jwt.issuer or jwt.audience would therefore disable claim validation with no
	 * error at all. Refuse to start instead: a service that cannot validate claims
	 * must not serve traffic.
	 */
	@jakarta.annotation.PostConstruct
	void assertClaimValidationIsConfigured() {
		if (jwtIssuer == null || jwtIssuer.isBlank()) {
			throw new IllegalStateException(
					"jwt.issuer must be set — a blank value silently disables issuer validation.");
		}
		if (jwtAudience == null || jwtAudience.isBlank()) {
			throw new IllegalStateException(
					"jwt.audience must be set — a blank value silently disables audience validation.");
		}
		if (secret != null
				&& secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException(
					"jwt.secret must be at least 32 bytes (256 bits) for HS256.");
		}
	}
}
