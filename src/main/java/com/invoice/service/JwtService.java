package com.invoice.service;

import com.invoice.entity.User;

public interface JwtService {
    
	 public String generateToken(User user);
	 public boolean validateToken(String token);
	 public String extractUsername(String token) ;
}
