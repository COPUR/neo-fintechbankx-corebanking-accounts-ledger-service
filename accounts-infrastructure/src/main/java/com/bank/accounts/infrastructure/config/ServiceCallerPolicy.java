package com.bank.accounts.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Which services may post journal entries (POST /api/v1/journal-entries).
 * The SERVICE realm role is held by every client-credentials client in the
 * realm, so on its own it would let any service move money; the token's
 * authorized party (azp, the calling client id) must also be on
 * accounts.security.service-callers (SERVICE_CALLERS, comma-separated).
 * Used from @PreAuthorize as {@code @serviceCallers.allowed(authentication)}.
 * <p>
 * Each listed service also owns the references that start with its prefixes
 * (accounts.security.service-reference-prefixes, SERVICE_REFERENCE_PREFIXES,
 * comma-separated client=prefix pairs; a client may appear more than once). A
 * service posts and looks up only its own references, so one service can neither
 * book under nor read another's business keys. No prefix may start another
 * client's prefix, and every listed service needs one, or the service does not start.
 */
@Component("serviceCallers")
public class ServiceCallerPolicy {

    static final String DEFAULT_CALLERS =
        "svc-pay-initiation-settlement,svc-pay-recurring-mandates,svc-pay-bulk-orchestration,svc-ln-loan-lifecycle";
    static final String DEFAULT_REFERENCE_PREFIXES = "svc-pay-initiation-settlement=payment-,"
        + "svc-pay-recurring-mandates=mandate-,svc-pay-bulk-orchestration=bulk-,svc-ln-loan-lifecycle=loan-";

    private final Set<String> allowedClients;
    private final Map<String, List<String>> referencePrefixes;

    public ServiceCallerPolicy(
            @Value("${accounts.security.service-callers:" + DEFAULT_CALLERS + "}") String allowedClients,
            @Value("${accounts.security.service-reference-prefixes:" + DEFAULT_REFERENCE_PREFIXES + "}")
            String referencePrefixes) {
        this.allowedClients = split(allowedClients).collect(Collectors.toUnmodifiableSet());
        this.referencePrefixes = prefixes(referencePrefixes);
        List<String> withoutPrefix = this.allowedClients.stream()
            .filter(client -> !this.referencePrefixes.containsKey(client)).sorted().toList();
        if (!withoutPrefix.isEmpty()) {
            throw new IllegalStateException("SERVICE_REFERENCE_PREFIXES has no prefix for " + withoutPrefix);
        }
    }

    public boolean allowed(Authentication authentication) {
        return client(authentication).filter(allowedClients::contains).isPresent();
    }

    /** True when the calling listed service owns the (trimmed) reference by its prefix. */
    public boolean ownsReference(Authentication authentication, String reference) {
        if (reference == null || !allowed(authentication)) {
            return false;
        }
        String trimmed = reference.trim();
        return client(authentication)
            .map(client -> referencePrefixes.getOrDefault(client, List.of()))
            .orElse(List.of())
            .stream().anyMatch(trimmed::startsWith);
    }

    private static Optional<String> client(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            return Optional.empty();
        }
        Object azp = token.getToken().getClaims().get("azp");
        return Optional.ofNullable(azp).map(Object::toString);
    }

    private static Map<String, List<String>> prefixes(String pairs) {
        Map<String, List<String>> byClient = new HashMap<>();
        Map<String, String> owner = new HashMap<>();
        split(pairs).forEach(pair -> {
            int equals = pair.indexOf('=');
            String client = equals < 0 ? "" : pair.substring(0, equals).trim();
            String prefix = equals < 0 ? "" : pair.substring(equals + 1).trim();
            if (client.isEmpty() || prefix.isEmpty()) {
                throw new IllegalStateException("SERVICE_REFERENCE_PREFIXES entry is not client=prefix: " + pair);
            }
            owner.forEach((other, otherClient) -> {
                if (!otherClient.equals(client) && (other.startsWith(prefix) || prefix.startsWith(other))) {
                    throw new IllegalStateException("Reference prefix " + prefix + " of " + client
                        + " overlaps " + other + " of " + otherClient);
                }
            });
            owner.put(prefix, client);
            byClient.computeIfAbsent(client, key -> new ArrayList<>()).add(prefix);
        });
        Map<String, List<String>> frozen = new HashMap<>();
        byClient.forEach((client, list) -> frozen.put(client, List.copyOf(list)));
        return Map.copyOf(frozen);
    }

    private static Stream<String> split(String value) {
        return Arrays.stream(value == null ? new String[0] : value.split(","))
            .map(String::trim)
            .filter(item -> !item.isEmpty());
    }
}
