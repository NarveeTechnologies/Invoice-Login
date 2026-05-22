package com.invoice.service;
import java.io.IOException;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {

	public String saveFile(MultipartFile file);

	public  Resource loadFile(String filename) throws IOException;

	public String updateLogo(Long createdBy, MultipartFile file) throws IOException;
}