package com.bank.accounts.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceCallerPolicyTest {

    @Test
    void onlyListedClientsPass() {
        var policy = new ServiceCallerPolicy(" svc-ln-loan-lifecycle, svc-pay-bulk-orchestration ,",
            "svc-ln-loan-lifecycle=loan-,svc-pay-bulk-orchestration=bulk-");

        assertThat(policy.allowed(token(Map.of("azp", "svc-ln-loan-lifecycle")))).isTrue();
        assertThat(policy.allowed(token(Map.of("azp", "svc-pay-bulk-orchestration")))).isTrue();
        assertThat(policy.allowed(token(Map.of("azp", "svc-rsk-decisioning")))).isFalse();
        assertThat(policy.allowed(token(Map.of()))).isFalse();
        assertThat(policy.allowed(new TestingAuthenticationToken("svc-ln-loan-lifecycle", "x", "ROLE_SERVICE"))).isFalse();
        assertThat(policy.allowed(null)).isFalse();
    }

    @Test
    void anEmptyListAllowsNoService() {
        assertThat(new ServiceCallerPolicy(" ", " ").allowed(token(Map.of("azp", "svc-ln-loan-lifecycle")))).isFalse();
        assertThat(new ServiceCallerPolicy(ServiceCallerPolicy.DEFAULT_CALLERS,
                ServiceCallerPolicy.DEFAULT_REFERENCE_PREFIXES)
            .allowed(token(Map.of("azp", "svc-ln-loan-lifecycle")))).isTrue();
    }

    @Test
    void aServiceOwnsOnlyTheReferencesWithItsPrefixes() {
        var policy = new ServiceCallerPolicy("svc-ln-loan-lifecycle,svc-pay-bulk-orchestration",
            " svc-ln-loan-lifecycle = loan- , svc-pay-bulk-orchestration=bulk-,svc-ln-loan-lifecycle=writeoff-");
        var loan = token(Map.of("azp", "svc-ln-loan-lifecycle"));

        assertThat(policy.ownsReference(loan, "loan-disbursement:LN-1")).isTrue();
        assertThat(policy.ownsReference(loan, "  loan-repayment:PAY-9 ")).isTrue();
        assertThat(policy.ownsReference(loan, "writeoff-LN-1")).isTrue();
        assertThat(policy.ownsReference(loan, "bulk-FILE-1/ITEM-7")).isFalse();
        assertThat(policy.ownsReference(loan, "LOAN-disbursement:LN-1")).isFalse();
        assertThat(policy.ownsReference(loan, "payment-loan-1")).isFalse();
        assertThat(policy.ownsReference(loan, null)).isFalse();
        assertThat(policy.ownsReference(token(Map.of("azp", "svc-pay-bulk-orchestration")), "bulk-FILE-1")).isTrue();
        assertThat(policy.ownsReference(token(Map.of("azp", "svc-rsk-decisioning")), "loan-x")).isFalse();
        assertThat(policy.ownsReference(token(Map.of()), "loan-x")).isFalse();
    }

    @Test
    void aPrefixMapThatLeavesACallerOutOrOverlapsDoesNotStart() {
        assertThatThrownBy(() -> new ServiceCallerPolicy("svc-ln-loan-lifecycle,svc-pay-bulk-orchestration",
                "svc-ln-loan-lifecycle=loan-"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("svc-pay-bulk-orchestration");
        assertThatThrownBy(() -> new ServiceCallerPolicy("a,b", "a=loan-,b=loan-dis"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("overlaps");
        assertThatThrownBy(() -> new ServiceCallerPolicy("a,b", "a=loan-dis,b=loan-"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("overlaps");
        assertThatThrownBy(() -> new ServiceCallerPolicy("a", "a"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("client=prefix");
        assertThatThrownBy(() -> new ServiceCallerPolicy("a", "a="))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("client=prefix");
        assertThat(new ServiceCallerPolicy("a", "a=loan-,a=loan-dis").ownsReference(
            token(Map.of("azp", "a")), "loan-x")).isTrue();
    }

    private static org.springframework.security.core.Authentication token(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject("service-account")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return SecurityConfiguration.keycloakRealmRoles().convert(builder.build());
    }
}
