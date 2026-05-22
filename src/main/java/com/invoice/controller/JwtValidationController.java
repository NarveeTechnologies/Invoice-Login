package com.invoice.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.invoice.entity.User;
import com.invoice.serviceImpl.JwtServiceImpl;
import com.invoice.serviceImpl.UserServiceImpl;

@RestController
@RequestMapping("/auth")
public class JwtValidationController {

	@Autowired
	private JwtServiceImpl jwtService;

	@Autowired
	private UserServiceImpl userService;

	@PostMapping("/validate-token")
	public ResponseEntity<Map<String, Object>> validateToken(@RequestHeader("Authorization") String authHeader) {
		if (authHeader == null || !authHeader.startsWith("Bearer ")) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.body(Map.of("error", "Missing or invalid Authorization header"));
		}

		String token = authHeader.substring(7);
		if (!jwtService.validateToken(token)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid or expired token"));
		}

		String email = jwtService.extractUsername(token);
		User user = userService.getUserByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));

		Map<String, Object> response = new HashMap<>();
		response.put("username", user.getEmail());
		response.put("roles", List.of(user.getRole().getRoleName().toUpperCase()));
		response.put("userId", user.getId());

		return ResponseEntity.ok(response);
	}
}
