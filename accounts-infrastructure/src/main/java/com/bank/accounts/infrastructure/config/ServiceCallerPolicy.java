package com.bank.accounts.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which services may post journal entries (POST /api/v1/journal-entries).
 * The SERVICE realm role is held by every client-credentials client in the
 * realm, so on its own it would let any service move money; the token's
 * authorized party (azp, the calling client id) must also be on
 * accounts.security.service-callers (SERVICE_CALLERS, comma-separated).
 * Used from @PreAuthorize as {@code @serviceCallers.allowed(authentication)}.
 */
@Component("serviceCallers")
public class ServiceCallerPolicy {

    static final String DEFAULT_CALLERS =
        "svc-pay-initiation-settlement,svc-pay-recurring-mandates,svc-pay-bulk-orchestration,svc-ln-loan-lifecycle";

    private final Set<String> allowedClients;

    public ServiceCallerPolicy(
            @Value("${accounts.security.service-callers:" + DEFAULT_CALLERS + "}") String allowedClients) {
        this.allowedClients = Arrays.stream(allowedClients == null ? new String[0] : allowedClients.split(","))
            .map(String::trim)
            .filter(client -> !client.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    public boolean allowed(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            return false;
        }
        Object azp = token.getToken().getClaims().get("azp");
        return azp != null && allowedClients.contains(azp.toString());
    }
}
