package com.invoice.tenant;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {

    private SecurityUtils() {}

    public static Long getCurrentAdminId() {
        Long fromContext = TenantContext.getCurrentAdminId();
        if (fromContext != null) return fromContext;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getDetails() instanceof Long adminFromDetails) return adminFromDetails;
        throw new SecurityIntegrityException(
                "No authenticated adminId available on this thread — security filter chain is misconfigured.");
    }

    public static String getCurrentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? String.valueOf(auth.getPrincipal()) : null;
    }

    public static boolean hasAuthority(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (ga.getAuthority().equalsIgnoreCase(authority)) return true;
        }
        return false;
    }

    public static void assertOwnedByCurrentTenant(Long resourceAdminId) {
        Long current = getCurrentAdminId();
        if (resourceAdminId == null || !current.equals(resourceAdminId)) {
            throw new SecurityIntegrityException(
                    "Cross-tenant access denied: resource adminId=" + resourceAdminId
                            + " does not match authenticated adminId=" + current);
        }
    }

    public static final class SecurityIntegrityException extends RuntimeException {
        public SecurityIntegrityException(String message) { super(message); }
    }
}
