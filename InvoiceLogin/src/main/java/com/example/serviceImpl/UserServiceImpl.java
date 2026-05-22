package com.invoice.serviceImpl;

import java.util.Optional;
import java.util.Random;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;


import com.invoice.DTO.LoginRequest;

import com.invoice.entity.OTP;
import com.invoice.entity.User;

import com.invoice.repository.TokenRepository;
import com.invoice.repository.UserRepository;
import com.invoice.service.UserService;

import jakarta.mail.internet.MimeMessage;
import jakarta.transaction.Transactional;

@Service
public class UserServiceImpl implements UserService {


    

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtServiceImpl jwtServiceImpl;

    @Autowired
    private TokenRepository tokenRepository;
    
    @Autowired
    private JavaMailSender javaMailSender;
    


  
    /**
     * Register new user (no password required)
     */
    @Override
    public User  register(User user) {
    	
    	if(userRepository.findByEmailIgnoreCase(user.getEmail()).isPresent()) {
    		throw new RuntimeException("Email is already registered!");
    	}
        return  userRepository.save(user);
    }    
    
    
    @Transactional
    @Override
    public void sendOtp(String email) {
        email = email.trim();
        System.out.println("Checking email: [" + email + "]");

        Optional<User> optionalUser = userRepository.findByEmailIgnoreCase(email);
        if (optionalUser.isEmpty()) {
            System.out.println("No user found in DB for: " + email);
            throw new RuntimeException("Invalid credentials: email not registered");
        }

        tokenRepository.deleteByEmail(email);

        // Generate OTP
        String otp = String.valueOf(new Random().nextInt(900000) + 100000);
        long expiryTime = System.currentTimeMillis() + 120000;
        tokenRepository.save(new OTP(null, email, otp, expiryTime));

        try {
            
            MimeMessage mimeMessage = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            helper.setFrom("no-reply@narvee.com"); 
            helper.setTo(email);                            
            helper.setSubject("Login Verification Code");
            helper.setText("Your OTP is: " + otp + " (valid for 2 mins)", false);

            javaMailSender.send(mimeMessage);
            System.out.println("OTP email sent successfully to: " + email);

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to send OTP email");
        }
    }
    
    @Override
    public boolean isOTPValid(String email, String otp) {
        Optional<OTP> optionalToken = tokenRepository.findByEmailAndOtp(email, otp);
        if (optionalToken.isEmpty()) {
            return false;
        }

        OTP token = optionalToken.get();

        // check expiry
        if (System.currentTimeMillis() > token.getExpiryTime()) {
            return false;
        }

        //  OTP is valid → now delete it
        tokenRepository.deleteByEmail(email);
        return true;
    }
    
    
    
    
    @Override
    public String loginWithOtp(LoginRequest request) {
        // Check if user exists
        Optional<User> optionalUser = userRepository.findByEmailIgnoreCase(request.getEmail());
        if (optionalUser.isEmpty()) {
            throw new RuntimeException("Invalid credentials: email not registered");
        }
        User user = optionalUser.get();

        // Validate OTP
        Optional<OTP> optionalToken = tokenRepository.findByEmailAndOtp(request.getEmail(), request.getOtp());
        if (optionalToken.isEmpty()) {
            throw new RuntimeException("Invalid OTP");
        }
        OTP token = optionalToken.get();

        // Check expiry
        if (System.currentTimeMillis() > token.getExpiryTime()) {
            throw new RuntimeException("OTP expired");
        }

        // OTP is valid → delete it (so it can't be reused)
        tokenRepository.deleteByEmail(request.getEmail());

        // Generate JWT token for session
        return jwtServiceImpl.generateToken(user);
    }

	
	
}
