package com.invoice.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.invoice.entity.CompanyRegistry;

import java.util.List;
import java.util.Optional;

public interface CompanyRegistryRepository extends JpaRepository<CompanyRegistry, Long> {
    Optional<CompanyRegistry> findByCompanyDomain(String companyDomain);
    List<CompanyRegistry> findAllByActiveTrue();
    boolean existsByCompanyDomain(String companyDomain);
}
