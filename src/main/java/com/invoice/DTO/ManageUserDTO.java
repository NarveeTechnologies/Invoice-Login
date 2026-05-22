package com.invoice.DTO;

import java.util.List;

import com.invoice.entity.BankDetails;

import jakarta.persistence.Column;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ManageUserDTO {

	private Long id;
	private String fullName;
	private String firstName;
	private String middleName;
	private String lastName;
	private String email;
	private String primaryEmail;
	private String mobileNumber;
	private String companyName;
	private String roleName;
	private String addedBy; // addedBy user ID (as String)
	private Long updatedBy; // updater user ID
	private String addedByName; // addedBy display name
	private String updatedByName; // updater display name
	private String businessCountry;
	private String suite;
	private String companylogo;
    private String companyDomain;

	private String state;
	private String country;
	private String city;
	private String pincode;
	private String telephone;
	private String ein;
	private String gstin;
	private String website;
	private String address;
	private String token;
	private String loginUrl;
	
	private long adminId;
	
	
	private String fid;
	private String everifyId;
	private String dunsNumber;
	private String stateOfIncorporation;
	private String naicsCode;
	private String signingAuthorityName;
	private String designation;
	private String dateOfIncorporation;
	
	private List<BankDetails> BankDetails;


	
}
