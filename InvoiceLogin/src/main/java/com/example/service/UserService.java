package com.invoice.service;




import com.invoice.DTO.LoginRequest;
import com.invoice.entity.User;


public interface UserService {
 
	
	
	public User register(User user) ;
	public String loginWithOtp(LoginRequest request);
    public void sendOtp(String email);//request OTP
    public boolean isOTPValid(String email, String otp);

}
