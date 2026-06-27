package com.invoice.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "updated_profile")
public class Admin {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private String fullName;
	@Column(unique = true, nullable = false)
	private String primaryEmail;
	private String alternativeEmail;
	private String mobileNumber;
	private String alternativeMobileNumber;
	private String companyName;
	private String taxId;
	private String businessId;
	private String prefferedCurrency;
	private String invoicePrefix;
	private Long AdminId;

	// General / Company settings
	private String timezone;
	private String dateFormat;
	private String fiscalYearStart;

	// Invoice settings
	private Integer invoiceStartingNumber;
	private Integer paymentTermsDays;
	@Column(columnDefinition = "TEXT")
	private String invoiceNotes;
	@Column(columnDefinition = "TEXT")
	private String invoiceFooter;
	private String periodFormat;
	private String defaultDescription;

	// Notification settings
	private Boolean emailReminders;
	private Boolean overdueAlerts;
	private Integer reminderDaysBefore;
	private String schedulerDay;
	private String schedulerTime;

	// CC email addresses for outgoing invoice emails
	private String ccAdminEmail;
	private String ccHrEmail;
	private String ccAccountsEmail;

	// Dashboard settings
	private Integer upcomingDueDays;
	private Integer dashboardPanelItems;

	// Appearance settings
	private Integer defaultItemsPerPage;
	private String defaultExportFormat;
	
	

}
