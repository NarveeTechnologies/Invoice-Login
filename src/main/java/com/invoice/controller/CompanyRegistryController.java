package com.invoice.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
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

	/** List all registered companies */
	@GetMapping
	public ResponseEntity<List<CompanyRegistry>> getAllCompanies() {
		return ResponseEntity.ok(companyRegistryRepository.findAll());
	}

	/** List only active companies */
	@GetMapping("/active")
	public ResponseEntity<List<CompanyRegistry>> getActiveCompanies() {
		return ResponseEntity.ok(companyRegistryRepository.findAllByActiveTrue());
	}

	/** Get a specific company by domain */
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
	@PostMapping("/{domain}/reprovision")
	public ResponseEntity<String> reprovision(@PathVariable("domain") String domain) {
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

	/** Deactivate a company */
	@PutMapping("/{domain}/deactivate")
	public ResponseEntity<String> deactivate(@PathVariable("domain") String domain) {
		return companyRegistryRepository.findByCompanyDomain(domain).map(c -> {
			c.setActive(false);
			companyRegistryRepository.save(c);
			return ResponseEntity.ok("Company '" + domain + "' deactivated.");
		}).orElse(ResponseEntity.notFound().build());
	}
}
