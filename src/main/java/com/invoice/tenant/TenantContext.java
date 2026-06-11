package com.invoice.tenant;

public class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();
    private static final ThreadLocal<Long> CURRENT_ADMIN_ID = new ThreadLocal<>();

    public static void setCurrentTenant(String schema) { CURRENT_TENANT.set(schema); }
    public static String getCurrentTenant() { return CURRENT_TENANT.get(); }
    public static void setCurrentAdminId(Long adminId) { CURRENT_ADMIN_ID.set(adminId); }
    public static Long getCurrentAdminId() { return CURRENT_ADMIN_ID.get(); }

    public static void clear() {
        CURRENT_TENANT.remove();
        CURRENT_ADMIN_ID.remove();
    }

    /** Convert a companyDomain (e.g. "acme.com") to a valid schema name ("acme_com"). */
    public static String toSchemaName(String companyDomain) {
        if (companyDomain == null) return null;
        return companyDomain.toLowerCase().replaceAll("[^a-z0-9]", "_");
    }
}
