package com.invoice.entity;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = { "roles" })
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
@Entity
@Table(name = "privileges")
public class Privilege {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
   
	@EqualsAndHashCode.Include   // ✅ ADD THIS
	@Column(name = "privilegeid")
	private Long id;

	private String name;
	private String cardType;

	@Transient
	private Boolean selected = false;

	private String status;
	private String category;
	private Long adminId;
	private Long addedBy;
	private Long updatedBy;
	private String addedByName;
	private String updatedByName;
	private LocalDateTime createdDate;
	private LocalDateTime updatedDate;

	@JsonIgnore
	@ManyToMany(mappedBy = "privileges", fetch = FetchType.LAZY)
	private Set<Role> roles = new HashSet<>();

	@PrePersist
	public void onCreate() {
		this.createdDate = LocalDateTime.now();
		if (this.status == null)
			this.status = "Active";
	}

	@PreUpdate
	public void onUpdate() {
		this.updatedDate = LocalDateTime.now();
	}
}
