package com.invoice.commons;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

public class RestAPIResponse {
	private String status;
	private String message;
	private Object data;
	private int pagesize;

	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "dd-MM-yyyy HH:mm:ss", timezone = "IST")
	private LocalDateTime timeStamp = LocalDateTime.now();

	// Constructors
	public RestAPIResponse() {
	}

	public RestAPIResponse(String status) {
		this.status = status;
	}

	public RestAPIResponse(String status, String message) {
		this.status = status;
		this.message = message;
	}

	public RestAPIResponse(String status, String message, Object data) {
		this.status = status;
		this.message = message;
		this.data = data;
	}

	// Getters and setters
	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public Object getData() {
		return data;
	}

	public void setData(Object data) {
		this.data = data;
	}

	public int getPagesize() {
		return pagesize;
	}

	public void setPagesize(int pagesize) {
		this.pagesize = pagesize;
	}

	public LocalDateTime getTimeStamp() {
		return timeStamp;
	}

	public void setTimeStamp(LocalDateTime timeStamp) {
		this.timeStamp = timeStamp;
	}
}
