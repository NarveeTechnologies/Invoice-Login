package com.invoice.DTO;

import java.time.LocalDateTime;
import java.util.Set;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoleDTO {
    private Long roleId;
    private String roleName;
    private String description;
    private String status;
    private Long adminId;
    private Long addedBy;
    private String addedByName;
    private Long updatedBy;
    private String updatedByName;
    private LocalDateTime createdDate;
    private LocalDateTime updatedDate;
    private Set<PrivilegeDTO> privileges;

    // Public constructor for JPQL query (cannot include privileges here)
    public RoleDTO(Long roleId, String roleName, String description, String status,
                   Long addedBy, String addedByName, Long updatedBy, String updatedByName,
                   LocalDateTime createdDate, LocalDateTime updatedDate) {
        this.roleId = roleId;
        this.roleName = roleName;
        this.description = description;
        this.status = status;
        this.addedBy = addedBy;
        this.addedByName = addedByName;
        this.updatedBy = updatedBy;
        this.updatedByName = updatedByName;
        this.createdDate = createdDate;
        this.updatedDate = updatedDate;
    }
}
