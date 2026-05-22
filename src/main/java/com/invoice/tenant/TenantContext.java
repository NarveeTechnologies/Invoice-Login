package com.invoice.tenant;

public class TenantContext {

	private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

	public static void setCurrentTenant(String schema) {
		CURRENT_TENANT.set(schema);
	}

	public static String getCurrentTenant() {
		return CURRENT_TENANT.get();
	}

	public static void clear() {
		CURRENT_TENANT.remove();
	}

	/**
	 * Convert a companyDomain (e.g. "narvee.com") to a valid schema name
	 * ("narvee_com").
	 */
	public static String toSchemaName(String companyDomain) {
		if (companyDomain == null)
			return null;
		return companyDomain.toLowerCase().replaceAll("[^a-z0-9]", "_");
	}
}
