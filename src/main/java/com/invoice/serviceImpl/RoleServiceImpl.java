package com.invoice.serviceImpl;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.invoice.DTO.PrivilegeDTO;
import com.invoice.DTO.RoleDTO;
import com.invoice.entity.ManageUsers;
import com.invoice.entity.Privilege;
import com.invoice.entity.Role;
import com.invoice.entity.User;
import com.invoice.repository.ManageUserRepository;
import com.invoice.repository.PrivilegeRepository;
import com.invoice.repository.RoleRepository;
import com.invoice.repository.UserRepository;
import com.invoice.service.RoleService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

@Service
public class RoleServiceImpl implements RoleService {

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PrivilegeRepository privilegeRepository;
    
    @Autowired
    private ManageUserRepository manageUserRepository;

    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private ManageUserRepository repository;
    
    @PersistenceContext
    private EntityManager entityManager;

    private static final Logger log = LoggerFactory.getLogger(RoleServiceImpl.class);

    @Override
    public RoleDTO createRole(RoleDTO roleDTO, String loggedInEmail) {

        User currentUser = userRepository.findByEmailIgnoreCase(loggedInEmail)
                .orElseThrow(() -> new RuntimeException("Logged-in user not found"));

        // ✅ Check duplicate role for same admin
        Optional<Role> existingRole = roleRepository
                .findByRoleNameIgnoreCaseAndAdminId(roleDTO.getRoleName(), roleDTO.getAdminId());

        if (existingRole.isPresent()) {
            throw new RuntimeException(
                    "Role '" + roleDTO.getRoleName() + "' already exists for this admin");
        }

        Role role = convertToEntity(roleDTO);

        role.setAddedBy(currentUser.getId());
        role.setAddedByName(currentUser.getFullName());
        role.setCreatedDate(LocalDateTime.now());

        Role saved = roleRepository.save(role);

        return convertToDTO(saved);
    }

    @Override
    @Transactional
    public RoleDTO updateRole(Long roleId, RoleDTO roleDTO, String loggedInEmail) {

        // 1️⃣ Get current logged-in user
        User currentUser = userRepository.findByEmailIgnoreCase(loggedInEmail)
                .orElseThrow(() -> new RuntimeException("Logged-in user not found: " + loggedInEmail));

        // 2️⃣ Fetch existing Role
        Role existing = roleRepository.findByIdWithPrivileges(roleId)
                .orElseThrow(() -> new RuntimeException("Role not found"));

        // 3️⃣ Update role fields
        existing.setRoleName(roleDTO.getRoleName());
        existing.setDescription(roleDTO.getDescription());
        existing.setStatus(roleDTO.getStatus());

        if (existing.getAddedBy() == null) {
            existing.setAddedBy(currentUser.getId());
            existing.setAddedByName(currentUser.getFullName());
        }

        existing.setUpdatedBy(currentUser.getId());
        existing.setUpdatedByName(currentUser.getFullName());

        // 4️⃣ Save role
        Role updated = roleRepository.save(existing);

        // ❌ STEP 5 REMOVED (NO SYNC REQUIRED)

        // 5️⃣ Return DTO
        return convertToDTO(updated);
    }



    //  Assign a single privilege to a role
    @Override
    public RoleDTO assignPrivilegeToRole(Long roleId, Long privilegeId, Long creatorId) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new RuntimeException("Role not found"));

        User creator = userRepository.findById(creatorId)
                .orElseThrow(() -> new RuntimeException("Creator not found"));

        Privilege privilege = privilegeRepository.findById(privilegeId)
                .orElseThrow(() -> new RuntimeException("Privilege not found"));

        if (role.getPrivileges() == null) {
            role.setPrivileges(new HashSet<>());
        }

        role.getPrivileges().add(privilege);
        Role updated = roleRepository.save(role);
        return convertToDTO(updated);
    }

    // ✅ Get all roles
    @Override
    public List<RoleDTO> getAllRoles() {
        return roleRepository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    // ✅ Get role by ID
    @Override
    public RoleDTO getRoleById(Long roleId) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new RuntimeException("Role not found"));
        return convertToDTO(role);
    }

    // ✅ Update privileges of a role
    @Transactional
    @Override
    public RoleDTO updateRolePrivileges(Long roleId, Set<Long> selectedPrivilegeIds, String category) {
        log.info("Updating privileges for Role ID: {} and Category: {}", roleId, category);
        log.info("Selected privilege IDs: {}", selectedPrivilegeIds);

        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new RuntimeException("Role not found with ID: " + roleId));

        // 🔹 Get currently assigned privileges
        Set<Privilege> currentPrivileges = new HashSet<>(role.getPrivileges());
        log.info("Current privileges count: {}", currentPrivileges.size());

        // 🔹 Fetch all privileges in this category
        Set<Privilege> categoryPrivileges = privilegeRepository.findByCategory(category);
        log.info("Found {} privileges in category '{}'", categoryPrivileges.size(), category);

        // 🔹 Fetch privileges selected by user
        Set<Privilege> selectedPrivileges = privilegeRepository.findAllById(selectedPrivilegeIds)
                .stream().collect(Collectors.toSet());

        // 🔹 Remove unchecked privileges only from this category
        currentPrivileges.removeIf(p ->
                categoryPrivileges.contains(p) && !selectedPrivilegeIds.contains(p.getId()));

        // 🔹 Add newly selected privileges
        currentPrivileges.addAll(selectedPrivileges);

        // 🔹 Save and update
        role.setPrivileges(currentPrivileges);
        Role updatedRole = roleRepository.save(role);

        log.info("Updated privileges for Role '{}'. Total privileges now: {}",
                updatedRole.getRoleName(), updatedRole.getPrivileges().size());

        return mapToDTO(updatedRole);
    }

    @Override
    public void deleteRole(Long roleId) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new RuntimeException("Role not found"));

        long assignedCount = userRepository.countByRole_RoleId(roleId);
        if (assignedCount > 0) {
            throw new RuntimeException("Cannot delete role — it is still assigned to " + assignedCount + " user(s).");
        }

        // Remove privileges association before deleting
        role.getPrivileges().clear();
        roleRepository.delete(role);
    }


    // ==============================
    // Helper Methods (DTO Mapping)
    // ==============================

    private RoleDTO convertToDTO(Role role) {

        // privileges are already loaded inside the transaction
        Set<PrivilegeDTO> privilegeDTOs = role.getPrivileges().stream()
                .map(p -> new PrivilegeDTO(
                        p.getId(),
                        p.getName(),
                        p.getCardType(),
                        true,
                        p.getStatus(),
                        p.getCategory()
                ))
                .collect(Collectors.toSet());

        return RoleDTO.builder()
                .roleId(role.getRoleId())
                .roleName(role.getRoleName())
                .adminId(role.getAdminId())
                .description(role.getDescription())
                .status(role.getStatus())
                .addedBy(role.getAddedBy())
                .addedByName(role.getAddedByName())
                .updatedBy(role.getUpdatedBy())
                .updatedByName(role.getUpdatedByName())
                .createdDate(role.getCreatedDate())
                .updatedDate(role.getUpdatedDate())
                .privileges(privilegeDTOs)
                .build();
    }



    private Role convertToEntity(RoleDTO dto) {
        Role role = new Role();
        role.setRoleId(dto.getRoleId());
        role.setRoleName(dto.getRoleName());
        role.setDescription(dto.getDescription());
        role.setStatus(dto.getStatus());
        role.setAdminId(dto.getAdminId());

        // Audit fields must be mapped
        role.setAddedBy(dto.getAddedBy());
        role.setAddedByName(dto.getAddedByName());
        role.setUpdatedBy(dto.getUpdatedBy());
        role.setUpdatedByName(dto.getUpdatedByName());
        role.setCreatedDate(dto.getCreatedDate());
        role.setUpdatedDate(dto.getUpdatedDate());

        // Privileges mapping
        if (dto.getPrivileges() != null) {
            Set<Privilege> privileges = dto.getPrivileges().stream()
                    .map(p -> privilegeRepository.findById(p.getId())
                            .orElseThrow(() -> new RuntimeException("Privilege not found with id: " + p.getId())))
                    .collect(Collectors.toSet());
            role.setPrivileges(privileges);
        }

        return role;
    }




    // ✅ Alternative mapping method used after updates
    private RoleDTO mapToDTO(Role role) {
        return RoleDTO.builder()
                .roleId(role.getRoleId())
                .roleName(role.getRoleName())
                .description(role.getDescription())
                .status(role.getStatus())
                .addedBy(role.getAddedBy())
                .adminId(role.getAdminId())
                .addedByName(role.getAddedByName())
                .updatedBy(role.getUpdatedBy())
                .updatedByName(role.getUpdatedByName())
                .createdDate(role.getCreatedDate())
                .updatedDate(role.getUpdatedDate())
                .privileges(
                        role.getPrivileges() != null
                                ? role.getPrivileges().stream()
                                .map(p -> PrivilegeDTO.builder()
                                        .id(p.getId())
                                        .name(p.getName())
                                        .cardType(p.getCardType())
                                        .selected(true)
                                        .status(p.getStatus())
                                        .category(p.getCategory())
                                        .build())
                                .collect(Collectors.toSet())
                                : Collections.emptySet()
                )
                .build();
    }


    public Page<RoleDTO> searchRoles(
            int page,
            int size,
            String sortBy,
            String sortDir,
            String keyword
    ) {

        boolean sortByUserName =
                "addedByName".equals(sortBy) || "updatedByName".equals(sortBy);

        Pageable pageable = PageRequest.of(
                page,
                size,
                sortByUserName
                        ? Sort.by("roleId") // dummy DB sort
                        : (sortDir.equalsIgnoreCase("desc")
                            ? Sort.by(sortBy).descending()
                            : Sort.by(sortBy).ascending())
        );

        Page<RoleDTO> dtoPage =
                (keyword == null || keyword.isBlank())
                        ? roleRepository.findAll(pageable).map(this::mapToDTO)
                        : roleRepository.searchAll(keyword, pageable).map(this::mapToDTO);

        // IN-MEMORY SORT (CORRECT WAY)
        if (sortByUserName) {
            Comparator<RoleDTO> comparator =
                    "addedByName".equals(sortBy)
                            ? Comparator.comparing(
                                RoleDTO::getAddedByName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                            )
                            : Comparator.comparing(
                                RoleDTO::getUpdatedByName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                            );

            if ("desc".equalsIgnoreCase(sortDir)) {
                comparator = comparator.reversed();
            }

            List<RoleDTO> sorted = dtoPage.getContent()
                    .stream()
                    .sorted(comparator)
                    .toList();

            return new PageImpl<>(
                    sorted,
                    pageable,
                    dtoPage.getTotalElements()
            );
        }

        return dtoPage;
    }

    @Override
    public List<RoleDTO> getRolesByAdminId(Long adminId) {

        List<Role> roles = roleRepository.findByAdminId(adminId);

        if (roles.isEmpty()) {
            throw new RuntimeException("No roles found for this admin");
        }

        return roles.stream()
                .map(this::convertToDTO)
                .toList();
    }

    
    
    
    @Override
    public Page<RoleDTO> searchRoles(
            int page,
            int size,
            String sortBy,
            String sortDir,
            String keyword,
            String loggedInEmail
    ) {

        // ✅ Get logged-in user from manage_users
        ManageUsers admin = repository.findByEmailIgnoreCase(loggedInEmail)
                .orElseThrow(() -> new RuntimeException("Admin user not found"));

        // ✅ IMPORTANT FIX: use adminId column, not manage_users.id
        Long adminId = admin.getAdminId();

        if (adminId == null) {
            throw new RuntimeException("AdminId is missing for logged-in user");
        }

        boolean sortByUserName =
                "addedByName".equalsIgnoreCase(sortBy)
                        || "updatedByName".equalsIgnoreCase(sortBy);

        Pageable pageable = PageRequest.of(
                page,
                size,
                sortByUserName
                        ? Sort.by("roleId")
                        : ("desc".equalsIgnoreCase(sortDir)
                        ? Sort.by(sortBy).descending()
                        : Sort.by(sortBy).ascending())
        );

        // ✅ Fetch roles by adminId
        Page<RoleDTO> dtoPage =
                (keyword == null || keyword.isBlank())
                        ? roleRepository.findByAdminId(adminId, pageable)
                        .map(this::mapToDTO)
                        : roleRepository.searchByAdminId(adminId, keyword, pageable)
                        .map(this::mapToDTO);

        // ✅ Keep old custom in-memory sorting
        if (sortByUserName) {

            Comparator<RoleDTO> comparator =
                    "addedByName".equalsIgnoreCase(sortBy)
                            ? Comparator.comparing(
                            RoleDTO::getAddedByName,
                            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                    )
                            : Comparator.comparing(
                            RoleDTO::getUpdatedByName,
                            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                    );

            if ("desc".equalsIgnoreCase(sortDir)) {
                comparator = comparator.reversed();
            }

            List<RoleDTO> sortedContent = dtoPage.getContent()
                    .stream()
                    .sorted(comparator)
                    .toList();

            return new PageImpl<>(sortedContent, pageable, dtoPage.getTotalElements());
        }

        return dtoPage;
    }
}
