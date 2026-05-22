package com.invoice.commons;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;

public class RestAPIResponse {
	public String status;
	public String message;
	public Object data;
	public int pagesize;
	@Temporal(TemporalType.DATE)
	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "dd-MM-yyyy", locale = "hi_IN", timezone = "IST")
	private LocalDateTime timeStamp;

	private RestAPIResponse() {
		timeStamp = LocalDateTime.now();
	}

	public RestAPIResponse(String status) {
		this();
		this.status = status;
	}

	public RestAPIResponse(String status, String message) {
		this();
		this.status = status;
		this.message = message;
	}

	public RestAPIResponse(int pagesize, Object data) {
		this();
		this.pagesize = pagesize;
		this.data = data;
	}

	public RestAPIResponse(String status, String message, Object data) {
		this();
		this.status = status;
		this.message = message;
		this.data = data;
	}
	public RestAPIResponse(String status,  Object data) {
		this();
		this.status = status;
		this.data = data;
	}
}
