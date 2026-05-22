package com.invoice.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String action;             
    private String entityName;          
    private Long entityId;             
    private String performedBy;        
    private Long performedById;         
    private String email;               
    private LocalDateTime timestamp;    

    @Column(length = 2000)
    private String details;             
}
