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
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.invoice.serviceImpl.JwtServiceImpl;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

	@Autowired
	private JwtServiceImpl jwtService;

	// List of public paths that do NOT require JWT
	private static final String[] PUBLIC_PATHS = { "/auth/login", "/auth/register", "/auth/login/send-otp",
			"/auth/check-token", "/auth/validate-token", "/companies" };

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		String path = request.getRequestURI();

		// Skip public endpoints
		for (String publicPath : PUBLIC_PATHS) {
			if (path.startsWith(publicPath)) {
				filterChain.doFilter(request, response);
				return;
			}
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

		List<SimpleGrantedAuthority> authorities = new ArrayList<>();

		// Add roles from token
		List<String> roles = claims.get("roles", List.class);
		if (roles != null) {
			roles.forEach(r -> authorities.add(new SimpleGrantedAuthority(r.toUpperCase())));
		}

		// Add privileges from token
		List<String> privileges = claims.get("privileges", List.class);
		if (privileges != null) {
			privileges.forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
		}

		// Build authentication token
		UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(email, null,
				authorities);
		authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

		SecurityContextHolder.getContext().setAuthentication(authToken);

		// Continue filter chain
		filterChain.doFilter(request, response);
	}

	private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"status\":\"Error\",\"message\":\"" + message + "\"}");
	}
}