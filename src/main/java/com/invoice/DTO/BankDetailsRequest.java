package com.invoice.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankDetailsRequest {

	private Long id;
	private String bankName;
	private String bankAccountNumber;
	private String routingNumber;
}
