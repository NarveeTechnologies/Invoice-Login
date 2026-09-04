package com.invoice.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.invoice.serviceImpl.JwtServiceImpl;
import com.invoice.tenant.TenantContext;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * JWT enforcement filter for the Login service. Public endpoints — login, register,
 * OTP, company-registry lookup — bypass auth. Every other request must carry a
 * valid Bearer token whose claims include the {@code adminId} tenant boundary.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

	@Autowired
	private JwtServiceImpl jwtService;

	private static final String[] PUBLIC_PATHS = {
			"/auth/login",
			"/auth/register",
			"/auth/login/send-otp",
			"/auth/register/send-otp",
			"/auth/login/verify-otp",
			"/auth/check-email/",
			"/auth/check-token",
			"/auth/validate-token",
			"/auth/get-registration-token",
			// "/companies" deliberately absent: see doFilterInternal. A prefix
			// entry here made every mutating /companies endpoint unauthenticated
			// and therefore permanently 403.
			"/actuator/health",
			"/actuator/info"
	};

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		String path = request.getRequestURI();

		for (String publicPath : PUBLIC_PATHS) {
			if (path.startsWith(publicPath)) {
				filterChain.doFilter(request, response);
				return;
			}
		}

		// "/companies" is a prefix match above, which swallowed the whole
		// subtree -- including POST /companies/{domain}/reprovision,
		// POST /companies/reprovision-all and PUT /companies/{domain}/deactivate.
		// No token was ever parsed on those paths, so their @PreAuthorize ran
		// against an anonymous context and they answered 403 to everyone: dead
		// endpoints rather than exploitable ones.
		//
		// Only the GET reads are pre-authentication by design (a login screen
		// resolving a tenant). Everything else under /companies must
		// authenticate, so the authorization on those handlers can actually
		// take effect.
		if (path.equals("/companies") || path.startsWith("/companies/")) {
			if ("GET".equalsIgnoreCase(request.getMethod())) {
				filterChain.doFilter(request, response);
				return;
			}
			// fall through: parse the token as for any other protected path
		}

		String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

		if (authHeader == null || !authHeader.startsWith("Bearer ")) {
			sendUnauthorized(response, "Missing Authorization header");
			return;
		}

		String token = authHeader.substring(7).trim();

		if (!jwtService.validateToken(token)) {
			sendUnauthorized(response, "Invalid or expired token");
			return;
		}

		Claims claims = jwtService.extractAllClaims(token);
		String email = claims.getSubject();

		if (email == null || email.isEmpty()) {
			sendUnauthorized(response, "JWT missing subject");
			return;
		}

		Long adminId = coerceLong(claims.get("adminId"));
		if (adminId == null) {
			sendUnauthorized(response, "JWT missing tenant context");
			return;
		}
		TenantContext.setCurrentAdminId(adminId);

		List<SimpleGrantedAuthority> authorities = new ArrayList<>();

		List<String> roles = claims.get("roles", List.class);
		if (roles != null) {
			roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r.toUpperCase())));
		}

		List<String> privileges = claims.get("privileges", List.class);
		if (privileges != null) {
			privileges.forEach(p -> authorities.add(new SimpleGrantedAuthority(p.toUpperCase())));
		}

		UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(email, null,
				authorities);
		authToken.setDetails(adminId);

		SecurityContextHolder.getContext().setAuthentication(authToken);

		try {
			filterChain.doFilter(request, response);
		} finally {
			TenantContext.clear();
			SecurityContextHolder.clearContext();
		}
	}

	private Long coerceLong(Object value) {
		if (value == null) return null;
		if (value instanceof Number n) return n.longValue();
		try { return Long.parseLong(value.toString().trim()); } catch (NumberFormatException e) { return null; }
	}

	private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"status\":\"Error\",\"message\":\"" + message + "\"}");
	}
}
