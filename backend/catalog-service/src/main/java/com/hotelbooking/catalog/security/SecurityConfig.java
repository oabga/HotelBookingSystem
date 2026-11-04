package com.hotelbooking.catalog.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class SecurityConfig {

    private static final MappedJwtClaimSetConverter CLAIM_CONVERTER =
            MappedJwtClaimSetConverter.withDefaults(Map.of());

    @Bean
    SecurityFilterChain catalogSecurityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/hotels", "/hotels/*/availability")
                        .hasAnyRole(StayHubJwtValidator.ROLE_USER, StayHubJwtValidator.ROLE_ADMIN)
                        .requestMatchers(HttpMethod.POST, "/hotels/*/room-types")
                        .hasRole(StayHubJwtValidator.ROLE_ADMIN)
                        .requestMatchers(HttpMethod.PUT, "/room-types/*/price")
                        .hasRole(StayHubJwtValidator.ROLE_ADMIN)
                        .requestMatchers(HttpMethod.GET, "/admin/hotels/*/inventory")
                        .hasRole(StayHubJwtValidator.ROLE_ADMIN)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${security.jwt.issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
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

    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(StayHubJwtValidator.CLAIM_ROLE);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
