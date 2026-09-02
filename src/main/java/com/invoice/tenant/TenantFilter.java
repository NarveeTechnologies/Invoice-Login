package com.invoice.tenant;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Key;

@Component
@Order(1)
@Slf4j
public class TenantFilter implements Filter {

	@Value("${jwt.secret}")
	private String jwtSecret;

    /** Expected token issuer. Not a secret; verified on every request. */
    @Value("${jwt.issuer}")
    private String jwtIssuer;

    /** Expected token audience. Not a secret; verified on every request. */
    @Value("${jwt.audience}")
    private String jwtAudience;

    /** Bounded tolerance for clock drift between issuer and verifier. */
    @Value("${jwt.clock-skew-seconds:30}")
    private long jwtClockSkewSeconds;


	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {

		HttpServletRequest httpRequest = (HttpServletRequest) request;
		String authHeader = httpRequest.getHeader("Authorization");

		try {
			if (authHeader != null && authHeader.startsWith("Bearer ")) {
				String token = authHeader.substring(7).trim();
				Claims claims = parseClaims(token);
				String companyDomain = (String) claims.get("companyDomain");
				if (companyDomain != null && !companyDomain.isBlank()) {
					TenantContext.setCurrentTenant(TenantContext.toSchemaName(companyDomain));
				}
			}
		} catch (Exception e) {
			log.debug("Tenant extraction skipped: {}", e.getMessage());
		}

		try {
			chain.doFilter(request, response);
		} finally {
			TenantContext.clear();
		}
	}

	private Claims parseClaims(String token) {
		Key key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
		return Jwts.parserBuilder().setSigningKey(key)
				.requireIssuer(jwtIssuer)
				.requireAudience(jwtAudience)
				.setAllowedClockSkewSeconds(jwtClockSkewSeconds)
				.build().parseClaimsJws(token).getBody();
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
		if (jwtSecret != null
				&& jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException(
					"jwt.secret must be at least 32 bytes (256 bits) for HS256.");
		}
	}
}
