package com.invoice.DTO;

import java.util.List;

import com.invoice.entity.BankDetails;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserUpdateRequest {
	private Long id;
	private String fullName;
	private String primaryEmail;
	private String email;
	private String alternativeEmail;
	private String mobileNumber;
	private String alternativeMobileNumber;
	private String taxId;
	private String businessId;
	private String preferredCurrency;
	private String invoicePrefix;
	private String companyName;
	private String profilePicPath;

	private String role;
	private String state;
	private String country;
	private String city;
	private String pincode;
	private String telephone;
	private String ein;
	private String gstin;
	private String website;
	private String address;
	private String businessCountry;
	private String suite;
	private String companylogo;
	private String companyDomain;

	private String fid;
	private String everifyId;
	private String dunsNumber;
	private String stateOfIncorporation;
	private String naicsCode;
	private String signingAuthorityName;
	private String designation;
	private String dateOfIncorporation;

	private List<BankDetails> bankDetails;

}
