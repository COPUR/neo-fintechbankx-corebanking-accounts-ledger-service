package com.bank.accounts.infrastructure.config;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Stateless OAuth2 resource server. Tokens come from the platform Keycloak
 * realm; realm roles become ROLE_* authorities for the @PreAuthorize rules
 * on AccountController and JournalEntryController. Actuator endpoints are served on the management port,
 * which is not exposed outside the pod network.
 *
 * Token validation (platform contract, addendum 2026-10-08): signature from
 * the realm JWKS, issuer, expiry, and aud containing this service's id
 * (OIDC_AUDIENCE, default svc-acc-accounts-ledger). Keycloak adds the
 * audience on every client allowed to call this service. DPoP is not
 * enforced: it is required only for open-finance TPP clients, not for
 * internal service and first-party callers.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Errors Spring resolves with sendError (malformed JSON, unknown enum
                // values, uncaught exceptions) are forwarded to /error; without this the
                // caller would see 403 instead of the real 400 or 500.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakRealmRoles())));
        return http.build();
    }

    static final String DEFAULT_AUDIENCE = "svc-acc-accounts-ledger";

    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
                          @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                          @Value("${accounts.security.audience:" + DEFAULT_AUDIENCE + "}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(tokenValidator(issuer, audience));
        return decoder;
    }

    /** Issuer, expiry and audience; the signature is checked by the decoder. */
    static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, String audience) {
        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException("accounts.security.audience (OIDC_AUDIENCE) must name this service");
        }
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
            aud -> aud != null && aud.contains(audience));
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator);
    }

    /** End-user customer id claim (platform contract "End-user and caller claims"). */
    public static final String CUSTOMER_ID_CLAIM = "customer_id";

    /**
     * Realm roles become ROLE_* authorities. The principal name is the
     * customer_id claim when the token has one (web, mobile and TPP tokens) and
     * sub otherwise (staff and service tokens), so authentication.getName() is
     * the customer id for every ownership check; under Keycloak, sub is a UUID.
     */
    public static Converter<Jwt, AbstractAuthenticationToken> keycloakRealmRoles() {
        return jwt -> {
            String customerId = jwt.getClaimAsString(CUSTOMER_ID_CLAIM);
            String name = customerId != null && !customerId.isBlank() ? customerId : jwt.getSubject();
            return new JwtAuthenticationToken(jwt, realmRoles(jwt), name);
        };
    }

    @SuppressWarnings("unchecked")
    static Collection<GrantedAuthority> realmRoles(Jwt jwt) {
        Object realmAccess = jwt.getClaims().get("realm_access");
        if (!(realmAccess instanceof Map<?, ?> access) || !(access.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return ((Collection<Object>) roles).stream()
            .map(String::valueOf)
            .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
            .toList();
    }
}
