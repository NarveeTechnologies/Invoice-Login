package com.invoice.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "company_registry", schema = "invoice")
@Data
@NoArgsConstructor
public class CompanyRegistry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(name = "company_domain", nullable = false, unique = true)
    private String companyDomain;

    @Column(name = "schema_name", nullable = false, unique = true)
    private String schemaName;

    @Column(name = "admin_email", nullable = false)
    private String adminEmail;

    @Column(name = "logo_url")
    private String logoUrl;
    

    @Column(name = "registered_at", nullable = false)
    private LocalDateTime registeredAt = LocalDateTime.now();

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public CompanyRegistry(String companyName, String companyDomain, String schemaName,
                           String adminEmail, String logoUrl) {
        this.companyName = companyName;
        this.companyDomain = companyDomain;
        this.schemaName = schemaName;
        this.adminEmail = adminEmail;
        this.logoUrl = logoUrl;
        this.registeredAt = LocalDateTime.now();
        this.active = true;
    }
    
}
