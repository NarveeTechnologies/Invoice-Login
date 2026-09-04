package com.invoice.exception;

/**
 * The requested object does not exist, or the caller may not see it.
 *
 * <p>Deliberately one exception for both. A caller who is refused a record in
 * another tenant must not be able to tell that the id exists — otherwise the
 * error code itself enumerates the platform's users, roles and companies.
 *
 * <p>Introduced because the tenant guards previously threw a bare
 * {@code RuntimeException}, which the global handler maps to 500. That denied
 * access correctly but reported a server fault for an authorization decision:
 * indistinguishable from a real defect in logs and monitoring, and alarming to
 * a caller who did nothing wrong.
 */
public class ResourceNotFoundException extends RuntimeException {

	public ResourceNotFoundException(String message) {
		super(message);
	}
}
