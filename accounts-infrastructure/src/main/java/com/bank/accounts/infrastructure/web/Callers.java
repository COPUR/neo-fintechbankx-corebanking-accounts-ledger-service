package com.bank.accounts.infrastructure.web;

import com.bank.accounts.domain.port.in.Caller;
import com.bank.accounts.infrastructure.config.SecurityConfiguration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Set;

/** Builds the use-case Caller from the request's token. */
final class Callers {

    /** SERVICE role from a client on SERVICE_CALLERS (token azp); see ServiceCallerPolicy. */
    static final String LISTED_SERVICE = "(hasRole('SERVICE') and @serviceCallers.allowed(authentication))";
    private static final Set<String> STAFF_ROLES = Set.of("ROLE_BANKER", "ROLE_ADMIN", "ROLE_SERVICE");

    private Callers() {
    }

    /** The calling client id (token azp), or "-" for a token without one. */
    static String client(Authentication authentication) {
        Object azp = authentication instanceof JwtAuthenticationToken token
            ? token.getToken().getClaims().get("azp")
            : null;
        return azp == null ? "-" : azp.toString();
    }

    static Caller of(Authentication authentication) {
        boolean staff = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(STAFF_ROLES::contains);
        if (staff) {
            return Caller.staff(authentication.getName());
        }
        Object customerId = authentication instanceof JwtAuthenticationToken token
            ? token.getToken().getClaims().get(SecurityConfiguration.CUSTOMER_ID_CLAIM)
            : null;
        if (!(customerId instanceof String value) || value.isBlank()) {
            throw new AccessDeniedException("A customer token without a customer_id claim holds no accounts");
        }
        return Caller.customer(value);
    }
}
