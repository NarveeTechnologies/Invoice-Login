package com.invoice.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;

import com.invoice.serviceImpl.FileStorageServiceImpl;

/**
 * Path-containment for {@code FileStorageServiceImpl.loadFile}.
 *
 * <p><strong>Why this is a unit test and not an endpoint test.</strong> The
 * integration suite has traversal probes against {@code GET /auth/{filename}},
 * and they pass — but a teeth-check showed they pass for the wrong reason. The
 * controller's tenant check runs first, and no tenant references
 * {@code ../../etc/passwd}, so the request is refused before the containment
 * check is ever reached. Removing the containment check broke nothing.
 *
 * <p>Two controls stacked in that order means the outer one masks the inner one,
 * so the inner one has to be tested where it lives. If the tenant check is ever
 * relaxed or reordered, this is what still fails.
 */
class FileStorageContainmentTest {

	private FileStorageServiceImpl service;
	private Path outsideFile;

	@BeforeEach
	void setUp() throws IOException {
		service = new FileStorageServiceImpl();

		// A real file outside the upload directory, so a successful traversal
		// would actually return something. Asserting against a path that does
		// not exist would pass whether or not containment is enforced.
		outsideFile = Paths.get("target", "containment-probe-20260904.txt").toAbsolutePath();
		Files.createDirectories(outsideFile.getParent());
		Files.writeString(outsideFile, "OUTSIDE-UPLOADS-20260904");
	}

	@AfterEach
	void tearDown() throws IOException {
		Files.deleteIfExists(outsideFile);
		Files.deleteIfExists(Paths.get("uploads", "containment-inside-20260904.txt"));
	}

	@Test
	@DisplayName("a traversing name cannot reach a file outside the upload directory")
	void traversalIsRefused() {
		// uploads/ is relative to the working directory, so ../target/<file>
		// resolves to the probe written above.
		assertThrows(IOException.class,
				() -> service.loadFile("../target/containment-probe-20260904.txt"),
				"loadFile served a file outside the upload directory");
	}

	@Test
	@DisplayName("deeper traversal is refused too")
	void deepTraversalIsRefused() {
		for (String name : new String[] {
				"../../etc/passwd",
				"../../../etc/passwd",
				"../pom.xml",
				"./../pom.xml",
				"subdir/../../pom.xml",
		}) {
			assertThrows(IOException.class, () -> service.loadFile(name),
					"loadFile did not refuse " + name);
		}
	}

	@Test
	@DisplayName("positive control: an ordinary name inside the directory still works")
	void ordinaryNameStillWorks() throws IOException {
		// Without this, "throw on everything" would pass both tests above.
		Files.createDirectories(Paths.get("uploads"));
		Files.writeString(Paths.get("uploads", "containment-inside-20260904.txt"), "INSIDE-20260904");

		Resource resource = service.loadFile("containment-inside-20260904.txt");
		assertTrue(resource.exists());
		assertEquals("INSIDE-20260904",
				new String(resource.getInputStream().readAllBytes()).trim());
	}

	@Test
	@DisplayName("a missing name inside the directory is still a not-found, not a traversal")
	void missingNameIsNotFound() {
		assertThrows(IOException.class, () -> service.loadFile("no-such-file-20260904.txt"));
	}
}
