package com.invoice.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
    /**
     * Internal database schema backing this tenant. Never serialised.
     *
     * <p>{@code GET /companies}, {@code /companies/active} and
     * {@code /companies/{domain}} are {@code permitAll} — they run before a user
     * has a session — and they return this entity directly. That put the schema
     * name of every tenant on the platform in an unauthenticated response.
     *
     * <p>It is infrastructure detail with no client use: it names the Postgres
     * schema an attacker would target, and combined with the domain list it
     * enumerates every tenant. Read server-side only, by
     * {@code SchemaProvisioningService}.
     */
    @JsonIgnore
    private String schemaName;

    @Column(name = "admin_email", nullable = false)
    /**
     * Tenant administrator's address. Never serialised, for the same reason as
     * {@link #schemaName}: these endpoints need no authentication, so this was
     * handing out an administrator's email for every tenant — PII, and a
     * ready-made target list for phishing or credential stuffing.
     */
    @JsonIgnore
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
