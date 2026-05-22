package com.invoice.serviceImpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.invoice.DTO.PrivilegeDTO;
import com.invoice.entity.Privilege;
import com.invoice.entity.Role;
import com.invoice.repository.PrivilegeRepository;
import com.invoice.repository.RoleRepository;
import com.invoice.service.PrivilegeService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

@Service
@Transactional
public class PrivilegeServiceImpl implements PrivilegeService {

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private PrivilegeRepository privilegeRepository;

	@Autowired
	private RoleRepository roleRepository;

	@Override
	public PrivilegeDTO createPrivilege(PrivilegeDTO dto) {
		Privilege privilege = Privilege.builder().name(dto.getName()).cardType(dto.getCardType())
				.status(dto.getStatus()).category(dto.getCategory()).build();
		Privilege saved = privilegeRepository.save(privilege);
		return convertToDTO(saved);
	}

	@Override
	public PrivilegeDTO updatePrivilege(Long id, PrivilegeDTO dto) {
		Privilege privilege = privilegeRepository.findById(id)
				.orElseThrow(() -> new RuntimeException("Privilege not found with ID: " + id));

		privilege.setName(dto.getName());
		privilege.setCardType(dto.getCardType());
		privilege.setStatus(dto.getStatus());
		privilege.setCategory(dto.getCategory());

		Privilege updated = privilegeRepository.save(privilege);
		return convertToDTO(updated);
	}

	// Safe Delete Privilege
	@Override
	@Transactional
	public void deletePrivilege(Long id) {
		Privilege privilege = privilegeRepository.findById(id)
				.orElseThrow(() -> new EntityNotFoundException("Privilege not found with ID: " + id));

		Set<Role> linkedRoles = new HashSet<>(privilege.getRoles());
		for (Role role : linkedRoles) {
			role.getPrivileges().remove(privilege);
			roleRepository.save(role);
		}

		entityManager.createNativeQuery("DELETE FROM role_privileges WHERE privilegeid = :pid").setParameter("pid", id)
				.executeUpdate();

		privilegeRepository.delete(privilege);
		privilegeRepository.flush();
		entityManager.clear();
	}

	@Override
	@Transactional
	public void deletePrivilegesByCategoryId(Long categoryId) {
		try {
			// 1️⃣ Fetch any privilege for the given ID to extract category name
			Privilege privilege = privilegeRepository.findById(categoryId)
					.orElseThrow(() -> new RuntimeException("Privilege not found with ID: " + categoryId));

			String category = privilege.getCategory();

			// 2️⃣ Get all privilege IDs belonging to that category
			List<Long> privilegeIds = privilegeRepository.findIdsByCategory(category);

			if (privilegeIds.isEmpty()) {
				throw new RuntimeException("No privileges found for category: " + category);
			}

			// 3️⃣ Delete join table records (role_privileges)
			entityManager.createNativeQuery("DELETE FROM role_privileges WHERE privilegeid IN (:ids)")
					.setParameter("ids", privilegeIds).executeUpdate();

			// 4️⃣ Delete privileges under this category
			privilegeRepository.deleteByCategory(category);

			// 5️⃣ Clear persistence context to avoid stale data
			entityManager.clear();

		} catch (Exception e) {
			throw new RuntimeException("Error deleting privileges for categoryId " + categoryId + ": " + e.getMessage(),
					e);
		}
	}

	@Override
	public List<PrivilegeDTO> getAllPrivileges() {
		return privilegeRepository.findAll().stream().map(this::convertToDTO).collect(Collectors.toList());
	}

	@Override
	public PrivilegeDTO getPrivilegeById(Long id) {
		return privilegeRepository.findById(id).map(this::convertToDTO)
				.orElseThrow(() -> new RuntimeException("Privilege not found with ID: " + id));
	}

	@Override
	public List<PrivilegeDTO> getPrivilegesByCategory(String category) {
		return privilegeRepository.findByCategoryIgnoreCase(category).stream().map(this::convertToDTO)
				.collect(Collectors.toList());
	}

	@Override
	public Map<String, List<PrivilegeDTO>> getAllPrivilegesGrouped() {
		List<Privilege> privileges = privilegeRepository.findAll();

		return privileges.stream().collect(Collectors.groupingBy(Privilege::getCategory,
				Collectors.mapping(this::convertToDTO, Collectors.toList())));
	}

	@Override
	public Map<String, List<PrivilegeDTO>> getPrivilegesByRole(Long roleId) {
		Role role = roleRepository.findById(roleId)
				.orElseThrow(() -> new RuntimeException("Role not found with ID: " + roleId));

		Map<String, List<PrivilegeDTO>> grouped = new HashMap<>();

		List<Privilege> allPrivileges = privilegeRepository.findAll();

		allPrivileges.forEach(privilege -> {
			String category = privilege.getCategory();
			grouped.putIfAbsent(category, new ArrayList<>());

			boolean selected = role.getPrivileges().contains(privilege);

			grouped.get(category)
					.add(PrivilegeDTO.builder().id(privilege.getId()).name(privilege.getName())
							.cardType(privilege.getCardType()).selected(selected).status(privilege.getStatus())
							.category(category).build());
		});

		return grouped;
	}

	@Override
	public Map<String, String> getEndpointPrivilegesMap() {
		return Collections.emptyMap(); // implement later if needed
	}

	private PrivilegeDTO convertToDTO(Privilege privilege) {
		return PrivilegeDTO.builder().id(privilege.getId()).name(privilege.getName()).cardType(privilege.getCardType())
				.selected(false).status(privilege.getStatus()).category(privilege.getCategory()).build();
	}

}
