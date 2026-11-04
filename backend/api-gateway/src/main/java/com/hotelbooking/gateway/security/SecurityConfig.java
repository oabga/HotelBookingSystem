package com.hotelbooking.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

import java.util.HashMap;
import java.util.Map;

/**
 * Gateway verifies the JWT independently of Catalog/Booking (docs/api-contract.md §3.3/§6):
 * signature via IAM's JWKS, issuer, expiry and the mandatory claims. It does not do role-based
 * authorization — that stays with each service — and it forwards the Authorization header as-is.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final MappedJwtClaimSetConverter CLAIM_CONVERTER =
            MappedJwtClaimSetConverter.withDefaults(Map.of());

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    @Value("${security.jwt.issuer}")
    private String issuer;

    @Bean
    SecurityWebFilterChain gatewaySecurityFilterChain(ServerHttpSecurity http) {
        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange
                        // Preflight phải qua trước JWT, không yêu cầu Bearer (docs/api-contract.md §6).
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.jwtDecoder(jwtDecoder())));
        return http.build();
    }

    private ReactiveJwtDecoder jwtDecoder() {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setClaimSetConverter(SecurityConfig::convertClaimsKeepingMissingIat);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), new StayHubJwtValidator()));
        return decoder;
    }

    private static Map<String, Object> convertClaimsKeepingMissingIat(Map<String, Object> claims) {
        Map<String, Object> converted = new HashMap<>(CLAIM_CONVERTER.convert(claims));
        if (!claims.containsKey(JwtClaimNames.IAT)) converted.remove(JwtClaimNames.IAT);
        return converted;
    }
}
