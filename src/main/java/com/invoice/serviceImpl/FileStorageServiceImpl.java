package com.invoice.serviceImpl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.invoice.entity.ManageUsers;
import com.invoice.entity.User;
import com.invoice.repository.ManageUserRepository;
import com.invoice.repository.UserRepository;
import com.invoice.service.FileStorageService;

@Service
public class FileStorageServiceImpl implements FileStorageService {

	// Use Path instead of String
	private final Path uploadDir = Paths.get("uploads");

	@Autowired
	private ManageUserRepository manageUsersRepository;

	@Autowired
	private UserRepository userRepository;

	public FileStorageServiceImpl() throws IOException {
		if (!Files.exists(uploadDir)) {
			Files.createDirectories(uploadDir);
		}
	}

	@Override
	public String saveFile(MultipartFile file) {
		try {
			String originalFileName = file.getOriginalFilename();
			String extension = originalFileName.substring(originalFileName.lastIndexOf("."));
			String fileName = UUID.randomUUID().toString() + extension;

			Path filePath = uploadDir.resolve(fileName); // ✅ resolve works here
			Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

			return fileName;

		} catch (Exception e) {
			throw new RuntimeException("File upload failed: " + e.getMessage());
		}
	}

	@Override
	public Resource loadFile(String filename) throws IOException {
		Path filePath = uploadDir.resolve(filename).normalize(); // ✅ works now
		UrlResource resource = new UrlResource(filePath.toUri());
		if (resource.exists() && resource.isReadable()) {
			return resource;
		} else {
			throw new IOException("File not found: " + filename);
		}
	}

	@Override
	public String updateLogo(Long createdById, MultipartFile file) throws IOException {

		// 1️⃣ Validate file
		if (file == null || file.isEmpty()) {
			throw new RuntimeException("Please upload a valid file");
		}

		// 2️⃣ Find ManageUsers by createdBy
		List<ManageUsers> users = manageUsersRepository.findByCreatedBy_Id(createdById);

		if (users.isEmpty()) {
			throw new RuntimeException("Admin not found with createdBy: " + createdById);
		}

		ManageUsers user = users.get(0);

		// 3️⃣ Create upload directory if not exists
		if (!Files.exists(uploadDir)) {
			Files.createDirectories(uploadDir);
		}

		// 4️⃣ Delete old logo
		if (user.getCompanylogo() != null && !user.getCompanylogo().isEmpty()) {

			Path oldLogoPath = uploadDir.resolve(user.getCompanylogo()).normalize();

			if (Files.exists(oldLogoPath)) {
				Files.delete(oldLogoPath);
			}
		}

		// 5️⃣ Generate unique filename
		String originalFileName = file.getOriginalFilename();
		String extension = "";

		if (originalFileName != null && originalFileName.contains(".")) {
			extension = originalFileName.substring(originalFileName.lastIndexOf("."));
		}

		String filename = UUID.randomUUID().toString() + extension;

		// 6️⃣ Save new file
		Path filePath = uploadDir.resolve(filename).normalize();

		Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

		// 7️⃣ Update ManageUsers table
		user.setCompanylogo(filename);
		manageUsersRepository.save(user);

		// 8️⃣ Update user_info table
		User adminUser = userRepository.findById(createdById)
				.orElseThrow(() -> new RuntimeException("Admin not found in user_info table"));

		adminUser.setCompanylogo(filename);
		userRepository.save(adminUser);

		return filename;
	}

}