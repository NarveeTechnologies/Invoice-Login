package com.invoice.DTO;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.persistence.Column;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RegisterRequest {

	@JsonProperty("firstName")
	private String firstName;

	@JsonProperty("middleName")
	private String middleName;

	@JsonProperty("lastName")
	private String lastName;

	@JsonProperty("email")
	private String email;

	@JsonProperty("mobileNumber")
	private String mobileNumber;

	@JsonProperty("companyName")
	private String companyName;
	
	@JsonProperty("state")
	private String state;

	@JsonProperty("country")
	private String country;
	
	@JsonProperty("city")
	private String city;

	@JsonProperty("pincode")
	private String pincode;

	@JsonProperty("telephone")
	private String telephone;

	@JsonProperty("ein")
	private String ein;

	@JsonProperty("gstin")
	private String gstin;
	
	@JsonProperty("businessCountry")
	private String businessCountry;
	
	@JsonProperty("suite")
	private String suite;
	
	@JsonProperty("companylogo")
	private String companylogo;

	@JsonProperty("primaryEmail")
	private String primaryEmail; 

	@JsonProperty("website")
	private String website;
	
	@JsonProperty("address")
	private String address;
	
	@JsonProperty("token")
	private String token;
	
	@JsonProperty("companyDomain")
    private String companyDomain;
	
	@JsonProperty("adminId")
	private long adminId;

	
}
