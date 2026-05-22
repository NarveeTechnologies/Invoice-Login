package com.invoice.DTO;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PrivilegeDTO {
    private Long id;
    private String name;
    private String cardType;
    private Boolean selected;
    private String status;
    private String category;
}
