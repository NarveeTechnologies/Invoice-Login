package com.invoice.service;

import java.util.List;

import com.invoice.entity.Admin;
import com.invoice.entity.User;

public interface AdminService {

	public Admin saveProfile(Admin admin);

	public List<Admin> getAll();

	public User getById(Long id);

	public Admin updateProfile(Long id, Admin updatedAdmin);

	public boolean deleteProfile(Long id);

	public Admin getSettings(Long adminId);

	public Admin updateSettings(Long adminId, Admin settings);

	public void resetSettings(Long adminId);
}
