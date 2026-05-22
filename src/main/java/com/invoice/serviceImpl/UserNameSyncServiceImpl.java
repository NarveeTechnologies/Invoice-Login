package com.invoice.serviceImpl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

import com.invoice.repository.ManageUserRepository;
import com.invoice.repository.RoleRepository;
import com.invoice.repository.UserRepository;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class UserNameSyncServiceImpl {
	
	 private static final Logger log = LoggerFactory.getLogger(UserNameSyncServiceImpl.class);

    private final RoleRepository roleRepository;
    private final ManageUserRepository manageUserRepository;
    private final UserRepository userRepository;

    public void syncUserFullName(Long userId, String fullName) {
    	
    	log.info("Syncing fullName '{}' for userId={}", fullName, userId);


        // 1️⃣ Roles table
        roleRepository.updateAddedByName(userId, fullName);
        roleRepository.updateUpdatedByName(userId, fullName);

        // 2️⃣ ManageUsers table
        manageUserRepository.updateAddedByName(userId, fullName);
        manageUserRepository.updateUpdatedByName(userId, fullName);

        // 3️⃣ User table (self reference)
        userRepository.updateFullName(userId, fullName);
    }
}
