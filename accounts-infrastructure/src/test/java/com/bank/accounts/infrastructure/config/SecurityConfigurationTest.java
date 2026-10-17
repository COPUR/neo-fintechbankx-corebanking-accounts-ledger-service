package com.bank.accounts.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigurationTest {

    @Test
    void keycloakRealmRolesBecomeSpringRoles() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("customer", "banker"))));

        assertThat(SecurityConfiguration.realmRoles(jwt)).extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_CUSTOMER", "ROLE_BANKER");
        assertThat(SecurityConfiguration.keycloakRealmRoles().convert(jwt).getName()).isEqualTo("user-1");
    }

    @Test
    void thePrincipalNameIsTheCustomerIdClaimWhenPresentAndSubOtherwise() {
        Jwt customer = jwt(Map.of("customer_id", "CUST-12345678",
            "realm_access", Map.of("roles", List.of("customer"))));
        Jwt staff = jwt(Map.of("realm_access", Map.of("roles", List.of("banker"))));

        assertThat(SecurityConfiguration.keycloakRealmRoles().convert(customer).getName()).isEqualTo("CUST-12345678");
        assertThat(SecurityConfiguration.keycloakRealmRoles().convert(staff).getName()).isEqualTo("user-1");
    }

    @Test
    void tokensWithoutRealmRolesGetNoAuthorities() {
        assertThat(SecurityConfiguration.realmRoles(jwt(Map.of("scope", "accounts:read")))).isEmpty();
        assertThat(SecurityConfiguration.realmRoles(jwt(Map.of("realm_access", Map.of())))).isEmpty();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject("user-1")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static final String ISSUER = "https://identity.example.internal/realms/fintechbankx";

    @Test
    void tokensMustNameThisServiceInTheAudience() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair keys = generator.generateKeyPair();
        org.springframework.security.oauth2.jwt.NimbusJwtDecoder decoder = org.springframework.security.oauth2.jwt.NimbusJwtDecoder
            .withPublicKey((java.security.interfaces.RSAPublicKey) keys.getPublic()).build();
        decoder.setJwtValidator(SecurityConfiguration.tokenValidator(ISSUER, "svc-acc-accounts-ledger"));

        assertThat(decoder.decode(sign(keys, ISSUER, List.of("svc-acc-accounts-ledger", "account"))).getSubject())
            .isEqualTo("user-1");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> decoder.decode(sign(keys, ISSUER, List.of("svc-ln-loan-lifecycle"))))
            .isInstanceOf(org.springframework.security.oauth2.jwt.JwtValidationException.class)
            .hasMessageContaining("aud");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> decoder.decode(sign(keys, ISSUER, null)))
            .isInstanceOf(org.springframework.security.oauth2.jwt.JwtValidationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> decoder.decode(sign(keys, "https://evil.example/realms/x",
                List.of("svc-acc-accounts-ledger"))))
            .isInstanceOf(org.springframework.security.oauth2.jwt.JwtValidationException.class);
    }

    @Test
    void anEmptyAudienceIsAConfigurationError() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SecurityConfiguration.tokenValidator(ISSUER, " "))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theDecoderBeanUsesTheConfiguredJwksIssuerAndAudience() {
        assertThat(new SecurityConfiguration().jwtDecoder("http://localhost:1/certs", ISSUER, "svc-acc-accounts-ledger"))
            .isNotNull();
    }

    private static String sign(java.security.KeyPair keys, String issuer, List<String> audience) throws Exception {
        com.nimbusds.jwt.JWTClaimsSet.Builder claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
            .subject("user-1").issuer(issuer)
            .issueTime(new java.util.Date()).expirationTime(new java.util.Date(System.currentTimeMillis() + 60_000));
        if (audience != null) {
            claims.audience(audience);
        }
        com.nimbusds.jwt.SignedJWT jwt = new com.nimbusds.jwt.SignedJWT(
            new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.RS256), claims.build());
        jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(keys.getPrivate()));
        return jwt.serialize();
    }
}
