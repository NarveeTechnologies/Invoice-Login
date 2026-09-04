package com.invoice.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import com.invoice.entity.CompanyRegistry;
import com.invoice.repository.CompanyRegistryRepository;
import com.invoice.tenant.SchemaProvisioningService;
import com.invoice.tenant.TenantContext;

import java.util.List;

@RestController
@RequestMapping("/companies")
@RequiredArgsConstructor
@Slf4j
public class CompanyRegistryController {

	private final CompanyRegistryRepository companyRegistryRepository;
	private final SchemaProvisioningService schemaProvisioningService;
	private final com.invoice.security.CallerTenant callerTenant;

	/**
	 * Refuses unless the caller is a platform operator or the domain is their
	 * own.
	 *
	 * <p>Every endpoint here was gated on {@code hasRole('ADMIN')} at most, and
	 * every company administrator holds that role — so a tenant admin could
	 * act on <em>any</em> company. 404 rather than 403, matching the rest of
	 * the platform, so the status cannot confirm that a domain is registered.
	 */
	private void assertOwnDomainOrPlatformOperator(String domain, Authentication authentication) {
		if (authentication == null) {
			// Cannot happen on a @PreAuthorize'd handler, but the read
			// endpoints on this controller ARE anonymous and an earlier version
			// of this check ran on one of them, producing a 500 from a null
			// Authentication rather than a refusal.
			throw new com.invoice.exception.ResourceNotFoundException("Company not found: " + domain);
		}
		com.invoice.security.CallerTenant.Resolved caller = callerTenant.resolve(authentication.getName());
		if (caller.isSuperAdmin()) {
			return;
		}
		String own = caller.user() == null ? null : caller.user().getCompanyDomain();
		if (own == null || !own.equalsIgnoreCase(domain)) {
			throw new com.invoice.exception.ResourceNotFoundException("Company not found: " + domain);
		}
	}

	/**
	 * List all registered companies.
	 *
	 * <p><strong>Deliberately unauthenticated.</strong> This runs before a user
	 * has a session — Angular's {@code auth.interceptor} and the gateway both
	 * treat {@code /companies} as a no-token path — so the login and
	 * registration screens can resolve a company before anyone signs in.
	 *
	 * <p>The sensitive fields are kept out at the entity instead:
	 * {@code schemaName} and {@code adminEmail} are {@code @JsonIgnore}d. What
	 * remains is company name, domain, logo and registration date.
	 *
	 * <p>That pre-authentication tenant list is still a residual disclosure
	 * (G-44) — but closing it means changing how the login screen resolves a
	 * company, which is a product decision, not a patch.
	 */
	@GetMapping
	public ResponseEntity<List<CompanyRegistry>> getAllCompanies() {
		return ResponseEntity.ok(companyRegistryRepository.findAll());
	}

	/** List only active companies. Pre-authentication, as above. */
	@GetMapping("/active")
	public ResponseEntity<List<CompanyRegistry>> getActiveCompanies() {
		return ResponseEntity.ok(companyRegistryRepository.findAllByActiveTrue());
	}

	/**
	 * Get a specific company by domain. Pre-authentication, as above — this is
	 * how a login screen resolves a tenant from the address someone typed.
	 */
	@GetMapping("/{domain}")
	public ResponseEntity<CompanyRegistry> getByDomain(@PathVariable("domain") String domain) {
		return companyRegistryRepository.findByCompanyDomain(domain).map(ResponseEntity::ok)
				.orElse(ResponseEntity.notFound().build());
	}

	/**
	 * Re-provision a specific company's schema across all services. Use this
	 * whenever new tables are added to a source schema — it will add the missing
	 * tables to the tenant schema (IF NOT EXISTS).
	 */
	@PreAuthorize("hasRole('SUPERADMIN') or hasRole('ADMIN') or hasAuthority('COMPANY_ADMIN')")
	@PostMapping("/{domain}/reprovision")
	public ResponseEntity<String> reprovision(@PathVariable("domain") String domain,
			Authentication authentication) {
		// This executes DDL against a tenant schema. The role gate alone let
		// any company administrator run it against any other company's schema
		// (G-43).
		assertOwnDomainOrPlatformOperator(domain, authentication);
		return companyRegistryRepository.findByCompanyDomain(domain).map(company -> {
			try {
				schemaProvisioningService.reprovisionTenantSchema(company.getSchemaName());
				return ResponseEntity.ok("Schema '" + company.getSchemaName() + "' re-provisioned successfully.");
			} catch (Exception e) {
				log.error("Reprovision failed for '{}': {}", domain, e.getMessage());
				return ResponseEntity.internalServerError().body("Reprovision failed: " + e.getMessage());
			}
		}).orElse(ResponseEntity.notFound().build());
	}

	/**
	 * Re-provision ALL registered company schemas. Call this after adding new
	 * entity tables to any service — all tenant schemas will be updated
	 * automatically.
	 */
	// SUPERADMIN only: this runs DDL against every tenant schema on the
	// platform. It is an operator task after a table is added, not something a
	// single company's administrator should be able to trigger.
	@PreAuthorize("hasRole('SUPERADMIN')")
	@PostMapping("/reprovision-all")
	public ResponseEntity<String> reprovisionAll() {
		List<CompanyRegistry> companies = companyRegistryRepository.findAll();
		int success = 0, failed = 0;
		for (CompanyRegistry company : companies) {
			try {
				schemaProvisioningService.reprovisionTenantSchema(company.getSchemaName());
				success++;
			} catch (Exception e) {
				log.error("Reprovision failed for '{}': {}", company.getCompanyDomain(), e.getMessage());
				failed++;
			}
		}
		return ResponseEntity.ok("Reprovisioned " + success + " schemas, " + failed + " failed.");
	}

	/**
	 * Deactivate a company. SUPERADMIN only.
	 *
	 * <p>This was the most serious of the three: gated on
	 * {@code hasRole('ADMIN')}, which every company administrator holds, so one
	 * tenant's admin could deactivate another company outright (G-42).
	 *
	 * <p>Restricted to the platform operator rather than "your own company":
	 * self-deactivation through an API is an irreversible-feeling action with
	 * no confirmation step, and nothing in the product asks for it.
	 */
	@PreAuthorize("hasRole('SUPERADMIN')")
	@PutMapping("/{domain}/deactivate")
	public ResponseEntity<String> deactivate(@PathVariable("domain") String domain) {
		return companyRegistryRepository.findByCompanyDomain(domain).map(c -> {
			c.setActive(false);
			companyRegistryRepository.save(c);
			return ResponseEntity.ok("Company '" + domain + "' deactivated.");
		}).orElse(ResponseEntity.notFound().build());
	}
}
