package com.invoice.entity;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_info")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({ "hibernateLazyInitializer", "handler" })
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String firstName;
	private String middleName;
	private String lastName;

	@Column(unique = true, nullable = false)
	private String email;
	private String mobileNumber;
	private String companyName;
	private String fullName;
	private Boolean active;
	private Boolean approved;

	@ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn(name = "roleid")
	private Role role;

	private String position;

	/**
	 * Who created this account. Never serialised.
	 *
	 * <p>Two reasons, and the second is the important one.
	 *
	 * <p>It made {@code spring.jpa.open-in-view=false} impossible. Endpoints such
	 * as {@code /auth/me} return this entity directly, so Jackson walks into the
	 * creator and then into <em>their</em> lazy collections. Fetching a level
	 * deeper does not help — the creator has a creator — so no entity graph can
	 * terminate it. Jackson's own reference chain said so plainly:
	 * {@code RestAPIResponse["data"] -> User["createdBy"] -> User["bankDetails"]}.
	 *
	 * <p>And it was disclosing another user's private data. With open-in-view
	 * enabled, Jackson lazily loaded the whole creator record on the way past, so
	 * {@code GET /auth/me} returned that person's full profile — <strong>bank
	 * account number and routing number included</strong> — to anyone who could
	 * read their own profile. Verified against the running service before this
	 * annotation was added.
	 *
	 * <p>Nothing consumed it: no DTO carries it, the Angular app never reads it,
	 * and it is only ever assigned server-side. The id and name of a creator are
	 * already exposed separately where they are actually wanted.
	 */
	@JsonIgnore
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by_id")
	private User createdBy;

	@Column(nullable = false)
	private String primaryEmail;

	@Column(name = "profile_pic_path")
	private String profilePicPath;
	private String alternativeEmail;
	private String alternativeMobileNumber;
	private String taxId;
	private String businessId;
	private String preferredCurrency;
	private String invoicePrefix;

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

	@Column(name = "loginurl")
	private String loginUrl;

	@Column(name = "businessCountry")
	private String businessCountry;

	@Column(name = "suite")
	private String suite;

	@Column(name = "companylogo")
	private String companylogo;

	@Column(name = "companyDomain")
	private String companyDomain;

	private String fid;
	private String everifyId;
	private String dunsNumber;
	private String stateOfIncorporation;
	private String naicsCode;
	private String signingAuthorityName;
	private String designation;
	private String dateOfIncorporation;

	@OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<BankDetails> bankDetails;

	@PrePersist
	public void prePersist() {
		if (this.primaryEmail == null && this.email != null) {
			this.primaryEmail = this.email;
		}
		if (this.fullName == null) {
			this.fullName = String.join(" ", firstName != null ? firstName : "", middleName != null ? middleName : "",
					lastName != null ? lastName : "").trim();
		}
	}
}
