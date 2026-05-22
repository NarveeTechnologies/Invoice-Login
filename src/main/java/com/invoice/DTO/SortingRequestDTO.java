package com.invoice.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SortingRequestDTO {
	
	private String sortField;
    private String sortOrder;
    private String keyword;
    private Integer pageNumber;
    private Integer pageSize;
}

