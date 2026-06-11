package com.invoice.exception;

import java.io.PrintStream;
import java.io.PrintWriter;

public class FileStorageException extends RuntimeException {

	public FileStorageException(String message) {
		super(message);
	}

}
