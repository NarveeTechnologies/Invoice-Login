package com.invoice.entity;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = { "privileges" })
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "roleId")
@Entity
@Table(name = "roles", uniqueConstraints = @UniqueConstraint(columnNames = { "role_name", "admin_id" }))
public class Role {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	@Column(name = "roleid")
	private Long roleId;

	@Column(name = "role_name", nullable = false)
	private String roleName; // ✅ keep this field

	private String description;
	private String status;
	private Long adminId;
	private Long addedBy;
	private Long updatedBy;
	private String addedByName;
	private String updatedByName;
	private LocalDateTime createdDate;
	private LocalDateTime updatedDate;

	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(name = "role_privileges", joinColumns = @JoinColumn(name = "roleid"), inverseJoinColumns = @JoinColumn(name = "privilegeid"))
	private Set<Privilege> privileges = new HashSet<>();

	@PrePersist
	public void onCreate() {
		this.createdDate = LocalDateTime.now();
		// The DB enforces ck_roles_status CHECK (status IN ('ACTIVE','INACTIVE')).
		// Normalize so a null/blank or mixed-case value (e.g. "Active") cannot
		// violate the constraint and get mis-reported as "Role already exists".
		this.status = normalizeStatus(this.status);

		if (this.roleName == null || this.roleName.isBlank()) {
			throw new IllegalStateException("roleName cannot be null or blank!");
		}
	}

	@PreUpdate
	public void onUpdate() {
		this.updatedDate = LocalDateTime.now();
		this.status = normalizeStatus(this.status);
	}

	/** Coerce status to the only values the DB check constraint accepts. */
	private static String normalizeStatus(String status) {
		if (status == null || status.isBlank()) {
			return "ACTIVE";
		}
		String upper = status.trim().toUpperCase();
		return upper.equals("INACTIVE") ? "INACTIVE" : "ACTIVE";
	}
}
