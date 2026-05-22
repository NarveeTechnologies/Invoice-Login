package com.invoice.serviceImpl;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.invoice.entity.Admin;
import com.invoice.entity.User;
import com.invoice.repository.AdminRepository;
import com.invoice.repository.UserRepository;
import com.invoice.service.AdminService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AdminServiceImpl implements AdminService {

	@Autowired
	private AdminRepository adminRepository;

	@Autowired
	private UserRepository repository;

	@Override
	public Admin saveProfile(Admin admin) {
		// Save a new profile or update an existing one if ID exists
		return adminRepository.save(admin);
	}

	@Override
	public List<Admin> getAll() {
		return adminRepository.findAll();
	}

	@Override
	public Admin updateProfile(Long id, Admin updatedAdmin) {
		// Update an existing admin; return null if not found
		return adminRepository.findById(id).map(admin -> {
			admin.setFullName(updatedAdmin.getFullName());
			admin.setMobileNumber(updatedAdmin.getMobileNumber());
			admin.setAlternativeMobileNumber(updatedAdmin.getAlternativeMobileNumber());
			admin.setCompanyName(updatedAdmin.getCompanyName());
			admin.setPrimaryEmail(updatedAdmin.getPrimaryEmail());
			admin.setAlternativeEmail(updatedAdmin.getAlternativeEmail());
			admin.setTaxId(updatedAdmin.getTaxId());
			admin.setBusinessId(updatedAdmin.getBusinessId());
			admin.setPrefferedCurrency(updatedAdmin.getPrefferedCurrency());
			admin.setInvoicePrefix(updatedAdmin.getInvoicePrefix());
			return adminRepository.save(admin);
		}).orElse(null); // return null if ID not found

	}

	@Override
	public boolean deleteProfile(Long id) {
		// Delete profile; return false if ID does not exist
		if (adminRepository.existsById(id)) {
			adminRepository.deleteById(id);
			return true;
		}
		return false;
	}

	@Override
	public User getById(Long id) {
		// Return null if admin not found
		// return adminRepository.findById(id).orElse(null);
		log.error("{}", id);

		User user = repository.findById(id).orElse(null);
		log.error("{}", user);
		return user;
	}

}
