package com.bank.accounts.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceCallerPolicyTest {

    @Test
    void onlyListedClientsPass() {
        var policy = new ServiceCallerPolicy(" svc-ln-loan-lifecycle, svc-pay-bulk-orchestration ,");

        assertThat(policy.allowed(token(Map.of("azp", "svc-ln-loan-lifecycle")))).isTrue();
        assertThat(policy.allowed(token(Map.of("azp", "svc-pay-bulk-orchestration")))).isTrue();
        assertThat(policy.allowed(token(Map.of("azp", "svc-rsk-decisioning")))).isFalse();
        assertThat(policy.allowed(token(Map.of()))).isFalse();
        assertThat(policy.allowed(new TestingAuthenticationToken("svc-ln-loan-lifecycle", "x", "ROLE_SERVICE"))).isFalse();
        assertThat(policy.allowed(null)).isFalse();
    }

    @Test
    void anEmptyListAllowsNoService() {
        assertThat(new ServiceCallerPolicy(" ").allowed(token(Map.of("azp", "svc-ln-loan-lifecycle")))).isFalse();
        assertThat(new ServiceCallerPolicy(ServiceCallerPolicy.DEFAULT_CALLERS)
            .allowed(token(Map.of("azp", "svc-ln-loan-lifecycle")))).isTrue();
    }

    private static org.springframework.security.core.Authentication token(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject("service-account")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return SecurityConfiguration.keycloakRealmRoles().convert(builder.build());
    }
}
