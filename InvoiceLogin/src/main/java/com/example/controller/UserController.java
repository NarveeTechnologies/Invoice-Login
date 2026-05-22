package com.invoice.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


import com.invoice.DTO.LoginRequest;
import com.invoice.commons.RestAPIResponse;
import com.invoice.entity.User;
import com.invoice.serviceImpl.UserServiceImpl;

//@CrossOrigin("*")
@RestController
@RequestMapping("/auth")
public class UserController {

	@Autowired
	private UserServiceImpl userServiceImpl;
	
	@PostMapping("/register")
	public ResponseEntity<RestAPIResponse> register(@RequestBody User user) {

	    try {
	    //    String result = userServiceImpl.register(user);  
	        return new ResponseEntity(new RestAPIResponse( "success","Registred Successfully",userServiceImpl.register(user)),HttpStatus.OK);
	    } catch (Exception e) {
	        return new ResponseEntity(new RestAPIResponse( "error","Registred  Failed "),HttpStatus.OK);
	    }
	}

	@PostMapping("/login/send-otp")
	public ResponseEntity<RestAPIResponse> sendOTP(@RequestBody Map<String, String> body) {
	    Map<String, Object> response = new HashMap<>();
	    try {
	        String email = body.get("email");
	        userServiceImpl.sendOtp(email);
	        response.put("status", "success");
	        response.put("message", "OTP sent successfully.");
	        return new ResponseEntity(new RestAPIResponse("success","otp sent successfully",email),HttpStatus.OK);
	    } catch (Exception e) {
	        response.put("status", "error");
	        response.put("message", e.getMessage());
	         return new ResponseEntity(new RestAPIResponse("failed", "Email is not presented", null),
					HttpStatus.BAD_REQUEST);
	    }
	}

	@PostMapping("/otp-verify")
	public ResponseEntity<RestAPIResponse> isOTPValid(@RequestBody Map<String, String> body) {
	    String email = body.get("email");
	    String otp = body.get("otp");

	    boolean isValid = userServiceImpl.isOTPValid(email, otp);

	    if (isValid) {
	        return new ResponseEntity(
	                new RestAPIResponse("success", "OTP verified successfully!",isValid),
	                HttpStatus.OK
	        );
	    } else {
	        return new ResponseEntity(
	                new RestAPIResponse("error", "OTP invalid or expired.",isValid),
	                HttpStatus.BAD_REQUEST
	        );
	    }
	}

	@PostMapping("/login")
	public ResponseEntity<RestAPIResponse> login(@RequestBody LoginRequest request) {
	    Map<String, Object> response = new HashMap<>();
	    try {
	        String jwtToken = userServiceImpl.loginWithOtp(request); // Correct service method
	        response.put("status", "success");
	        response.put("message", "Login successful!");
	        response.put("token", jwtToken);  // send JWT to frontend
	        return new ResponseEntity<>(new RestAPIResponse("success", "Login Successfully",request),HttpStatus.OK);
	    } catch (Exception e) {
	        response.put("status", "error");
	        response.put("message", e.getMessage());
	        return new ResponseEntity(new RestAPIResponse("error", "Login failed please check your otp",null),HttpStatus.BAD_REQUEST);
	    }
	
	}
	
}
