package com.invoice.exception;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.invoice.tenant.SecurityUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	// Business Exception Handler
	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<String> handleBusinessException(BusinessException ex) {
		return new ResponseEntity<>(ex.getMessage(), HttpStatus.BAD_REQUEST);
	}

	// File Storage Exception Handler
	@ExceptionHandler(FileStorageException.class)
	public ResponseEntity<String> handleFileStorageException(FileStorageException ex) {
		return new ResponseEntity<>(ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
	}

	@ExceptionHandler(SecurityUtils.SecurityIntegrityException.class)
	public ResponseEntity<Map<String, Object>> handleSecurityIntegrity(
			SecurityUtils.SecurityIntegrityException ex, HttpServletRequest request) {
		// The detail stays in the log. The response carries a fixed string
		// rather than ex.getMessage(), which read
		// "resource adminId=X does not match authenticated adminId=Y" and named
		// the mechanism back to the caller. Nothing there belonged to a third
		// party -- both ids are the caller's own or supplied by them -- but the
		// refusals elsewhere on this platform name no mechanism, and this one
		// should not either.
		log.warn("Security integrity violation at {}: {}", request.getRequestURI(), ex.getMessage());
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(body(HttpStatus.FORBIDDEN, "Access denied", request));
	}

	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<Map<String, Object>> handleAccessDenied(
			AccessDeniedException ex, HttpServletRequest request) {
		log.warn("Access denied at {}: {}", request.getRequestURI(), ex.getMessage());
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(body(HttpStatus.FORBIDDEN,
						"You do not have permission to perform this action.", request));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> handleMethodArgNotValid(
			MethodArgumentNotValidException ex, HttpServletRequest request) {
		List<Map<String, String>> fieldErrors = new ArrayList<>();
		ex.getBindingResult().getAllErrors().forEach(error -> {
			Map<String, String> fe = new LinkedHashMap<>();
			fe.put("field", error instanceof FieldError ? ((FieldError) error).getField() : error.getObjectName());
			fe.put("message", error.getDefaultMessage());
			fieldErrors.add(fe);
		});
		log.warn("Validation failed at {}: {}", request.getRequestURI(), fieldErrors);
		Map<String, Object> resp = body(HttpStatus.BAD_REQUEST, "Validation failed", request);
		resp.put("errors", fieldErrors);
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(resp);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<Map<String, Object>> handleConstraintViolation(
			ConstraintViolationException ex, HttpServletRequest request) {
		log.warn("Constraint violation at {}: {}", request.getRequestURI(), ex.getMessage());
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(body(HttpStatus.BAD_REQUEST, ex.getMessage(), request));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, Object>> handleIllegalArgument(
			IllegalArgumentException ex, HttpServletRequest request) {
		log.warn("Illegal argument at {}: {}", request.getRequestURI(), ex.getMessage());
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(body(HttpStatus.BAD_REQUEST, ex.getMessage(), request));
	}

	@ExceptionHandler(OptimisticLockingFailureException.class)
	public ResponseEntity<Map<String, Object>> handleOptimisticLocking(
			OptimisticLockingFailureException ex, HttpServletRequest request) {
		log.warn("Optimistic locking failure at {}: {}", request.getRequestURI(), ex.getMessage());
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(body(HttpStatus.CONFLICT, "Resource was modified by another transaction", request));
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<Map<String, Object>> handleDataIntegrity(
			DataIntegrityViolationException ex, HttpServletRequest request) {
		log.warn("Data integrity violation at {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(body(HttpStatus.CONFLICT, "Data integrity violation", request));
	}

	/**
	 * Mail delivery failure is a transient downstream problem, not a client error:
	 * 503 tells the caller to retry, and the frontend already renders that as
	 * "Service not available. Please try again later." The cause is logged, never
	 * returned — an SMTP error string can name the mail host and account.
	 */
	@ExceptionHandler(MailDeliveryException.class)
	public ResponseEntity<Map<String, Object>> handleMailDelivery(
			MailDeliveryException ex, HttpServletRequest request) {
		log.error("Mail delivery failed at {}: {}", request.getRequestURI(), ex.getMessage(), ex);
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(body(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request));
	}

	/**
	 * A passcode request refused by a ceiling.
	 *
	 * <p>429 with Retry-After, so the client can disable its resend button for
	 * the right interval instead of guessing. The body names no ceiling: telling
	 * an abusive caller whether they hit the per-address or the per-IP limit
	 * tells them how to spread the next attempt.
	 */
	@ExceptionHandler(com.invoice.otp.OtpRateLimitedException.class)
	public ResponseEntity<Map<String, Object>> handleOtpRateLimited(
			com.invoice.otp.OtpRateLimitedException ex, HttpServletRequest request) {
		log.warn("OTP rate limit hit at {} retryAfterSeconds={}",
				request.getRequestURI(), ex.getRetryAfterSeconds());
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
				.header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
				.body(body(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), request));
	}

	/**
	 * 404 for "does not exist" and for "exists but not yours" alike — see
	 * {@link ResourceNotFoundException}.
	 */
	@ExceptionHandler(ResourceNotFoundException.class)
	public ResponseEntity<Map<String, Object>> handleNotFound(
			ResourceNotFoundException ex, HttpServletRequest request) {
		log.info("Not found or not permitted at {}: {}", request.getRequestURI(), ex.getMessage());
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(body(HttpStatus.NOT_FOUND, ex.getMessage(), request));
	}

	// Generic Exception Handler
	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> handleGlobalException(Exception ex, HttpServletRequest request) {
		String path = request.getRequestURI();
		log.error("Unhandled exception at {}", path, ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(body(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request));
	}

	private Map<String, Object> body(HttpStatus status, String message, HttpServletRequest request) {
		Map<String, Object> b = new LinkedHashMap<>();
		b.put("status", status.value());
		b.put("message", message);
		b.put("timestamp", Instant.now().toString());
		b.put("path", request.getRequestURI());
		return b;
	}

}
