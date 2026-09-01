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
import com.invoice.tenant.SecurityUtils;

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

	private Optional<Admin> findAdminProfile(Long adminId) {
		Optional<Admin> found = adminRepository.findById(adminId);
		if (found.isEmpty()) {
			String email = SecurityUtils.getCurrentUserEmail();
			if (email != null) {
				found = adminRepository.findByEmailIgnoreCase(email);
			}
		}
		return found;
	}

	@Override
	public Admin getSettings(Long adminId) {
		// Never insert on a GET — return empty object if no profile exists yet
		return findAdminProfile(adminId).orElseGet(Admin::new);
	}

	@Override
	public Admin updateSettings(Long adminId, Admin settings) {
		String email = SecurityUtils.getCurrentUserEmail();
		Admin admin = findAdminProfile(adminId).orElseGet(() -> {
			Admin a = new Admin();
			a.setPrimaryEmail(email);
			a.setAdminId(adminId);
			return a;
		});
		if (settings.getTimezone() != null)
			admin.setTimezone(settings.getTimezone());
		if (settings.getDateFormat() != null)
			admin.setDateFormat(settings.getDateFormat());
		if (settings.getFiscalYearStart() != null)
			admin.setFiscalYearStart(settings.getFiscalYearStart());
		if (settings.getInvoiceStartingNumber() != null)
			admin.setInvoiceStartingNumber(settings.getInvoiceStartingNumber());
		if (settings.getPaymentTermsDays() != null)
			admin.setPaymentTermsDays(settings.getPaymentTermsDays());
		if (settings.getInvoiceNotes() != null)
			admin.setInvoiceNotes(settings.getInvoiceNotes());
		if (settings.getInvoiceFooter() != null)
			admin.setInvoiceFooter(settings.getInvoiceFooter());
		if (settings.getPeriodFormat() != null)
			admin.setPeriodFormat(settings.getPeriodFormat());
		if (settings.getDefaultDescription() != null)
			admin.setDefaultDescription(settings.getDefaultDescription());
		if (settings.getEmailReminders() != null)
			admin.setEmailReminders(settings.getEmailReminders());
		if (settings.getOverdueAlerts() != null)
			admin.setOverdueAlerts(settings.getOverdueAlerts());
		if (settings.getReminderDaysBefore() != null)
			admin.setReminderDaysBefore(settings.getReminderDaysBefore());
		if (settings.getSchedulerDay() != null)
			admin.setSchedulerDay(settings.getSchedulerDay());
		if (settings.getSchedulerTime() != null)
			admin.setSchedulerTime(settings.getSchedulerTime());
		if (settings.getCcAdminEmail() != null)
			admin.setCcAdminEmail(settings.getCcAdminEmail());
		if (settings.getCcHrEmail() != null)
			admin.setCcHrEmail(settings.getCcHrEmail());
		if (settings.getCcAccountsEmail() != null)
			admin.setCcAccountsEmail(settings.getCcAccountsEmail());
		if (settings.getUpcomingDueDays() != null)
			admin.setUpcomingDueDays(settings.getUpcomingDueDays());
		if (settings.getDashboardPanelItems() != null)
			admin.setDashboardPanelItems(settings.getDashboardPanelItems());
		if (settings.getDefaultItemsPerPage() != null)
			admin.setDefaultItemsPerPage(settings.getDefaultItemsPerPage());
		if (settings.getDefaultExportFormat() != null)
			admin.setDefaultExportFormat(settings.getDefaultExportFormat());
		if (settings.getPrefferedCurrency() != null)
			admin.setPrefferedCurrency(settings.getPrefferedCurrency());
		if (settings.getInvoicePrefix() != null)
			admin.setInvoicePrefix(settings.getInvoicePrefix());
		return adminRepository.save(admin);
	}

	@Override
	public void resetSettings(Long adminId) {
		findAdminProfile(adminId).ifPresent(admin -> {
			admin.setDateFormat(null);
			admin.setPrefferedCurrency(null);
			admin.setInvoicePrefix(null);
			admin.setPaymentTermsDays(null);
			admin.setPeriodFormat(null);
			admin.setDefaultDescription(null);
			admin.setEmailReminders(null);
			admin.setOverdueAlerts(null);
			admin.setReminderDaysBefore(null);
			admin.setCcAdminEmail(null);
			admin.setCcHrEmail(null);
			admin.setCcAccountsEmail(null);
			admin.setUpcomingDueDays(null);
			admin.setDefaultItemsPerPage(null);
			admin.setDefaultExportFormat(null);
			adminRepository.save(admin);
		});
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
