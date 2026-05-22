package com.invoice.entity;

import java.time.LocalDateTime;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "manage_users", uniqueConstraints = { @UniqueConstraint(columnNames = "email") })
@JsonIgnoreProperties({ "hibernateLazyInitializer", "handler" })
public class ManageUsers {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "first_name")
	private String firstName;

	@Column(name = "middle_name")
	private String middleName;

	@Column(name = "last_name")
	private String lastName;

	@Column(name = "full_name", nullable = false)
	@NotBlank(message = "Full name is mandatory")
	private String fullName;

	@Column(name = "company_domain", nullable = false)
	@NotBlank(message = "Company domain is mandatory")
	private String companyDomain;

	@Column(name = "company_name")
	private String companyName;

	@Column(name = "email", nullable = false, unique = true)
	@NotBlank(message = "Email is mandatory")
	private String email;

	@Column(name = "primary_email")
	private String primaryEmail;

	@Column(name = "mobile_number")
	private String mobileNumber;

	@Column(name = "role_name")
	private String roleName;

	@ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn(name = "roleid")
	private Role role;

	@ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn(name = "added_by_user_id")
	private User addedBy;

	@ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn(name = "created_by_user_id")
	@JsonIgnore
	private User createdBy;

	@Column(name = "added_by_name")
	private String addedByName;

	@Column(name = "updated_by")
	private Long updatedBy;

	@Column(name = "updated_by_name")
	private String updatedByName;

	@Column(name = "approved")
	private Boolean approved = false;

	@Column(name = "active")
	private Boolean active = true;

	private String invoicePrefix;
	
	@Column(name = "businessCountry")
	private String businessCountry;

	@Column(name = "state")
	private String state;

	@Column(name = "country")
	private String country;

	@Column(name = "city")
	private String city;

	@Column(name = "pincode")
	private String pincode;

	@Column(name = "telephone")
	private String telephone;

	@Column(name = "ein")
	private String ein;

	@Column(name = "gstin")
	private String gstin;

	@Column(name = "website")
	private String website;

	@Column(name = "address")
	private String address;

	@Column(name = "token")
	private String token;

	@Column(name = "loginurl")
	private String loginUrl;
	
	@Column(name = "suite")
	private String suite;
	
	@Column(name = "companylogo")
	private String companylogo;
	
	@Column(name = "admin_id")
	private Long adminId;


	private String fid;
	private String everifyId;
	private String dunsNumber;
	private String stateOfIncorporation;
	private String naicsCode;
	private String signingAuthorityName;
	private String designation;
	private String dateOfIncorporation;
	private String taxId;
	
	@ElementCollection(fetch = FetchType.EAGER)
	private List<BankDetails> bankDetails;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at")
	private LocalDateTime updatedAt;

	@PrePersist
	@PreUpdate
	public void normalizeAndValidate() {

		// Normalize names 
		this.firstName = capitalize(this.firstName);
		this.middleName = capitalize(this.middleName);
		this.lastName = capitalize(this.lastName);

		// Compute full name if missing
		if (!hasText(this.fullName)) {
			this.fullName = buildFullName();
		}

		// Normalize emails
		if (this.email != null) {
			this.email = this.email.trim().toLowerCase();
		}
		if (this.primaryEmail != null) {
			this.primaryEmail = this.primaryEmail.trim().toLowerCase();
		}

		// Validate required fields
		if (!hasText(companyDomain)) {
			throw new IllegalStateException("companyDomain must not be null or blank");
		}
		if (!hasText(email)) {
			throw new IllegalStateException("email must not be null or blank");
		}
		if (!hasText(fullName)) {
			throw new IllegalStateException("fullName must not be null or blank");
		}

	}

	private String buildFullName() {

		StringBuilder sb = new StringBuilder();

		if (hasText(firstName))
			sb.append(firstName.trim());
		if (hasText(middleName))
			sb.append(" ").append(middleName.trim());
		if (hasText(lastName))
			sb.append(" ").append(lastName.trim());

		return sb.toString().trim();
	}

	private String capitalize(String value) {

		if (!hasText(value))
			return value;

		value = value.trim();
		return value.substring(0, 1).toUpperCase() + value.substring(1).toLowerCase();
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	

}
