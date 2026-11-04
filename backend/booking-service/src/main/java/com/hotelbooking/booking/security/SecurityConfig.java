package com.hotelbooking.booking.security;

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

/**
 * Xác thực và phân quyền của booking-service.
 * Service tự kiểm tra JWT, kể cả khi bị gọi thẳng vào :8082 không qua Gateway.
 *
 * Tạm đặt ở đây vì common-security chưa có code. Khi module đó hoàn thành, có thể thay class này.
 */
@Configuration
public class SecurityConfig {

    private static final MappedJwtClaimSetConverter DEFAULT_CLAIM_CONVERTER =
            MappedJwtClaimSetConverter.withDefaults(Map.of());

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Đặt trước các rule chung: "/bookings/*" cũng khớp "/bookings/reschedulable".
                        .requestMatchers(HttpMethod.GET, "/bookings/reschedulable")
                        .hasRole(StayHubJwtValidator.ROLE_USER)
                        .requestMatchers(HttpMethod.POST, "/bookings/*/reschedule")
                        .hasRole(StayHubJwtValidator.ROLE_ADMIN)
                        .requestMatchers(HttpMethod.GET, "/bookings/*/reschedule-quote")
                        .hasAnyRole(StayHubJwtValidator.ROLE_USER, StayHubJwtValidator.ROLE_ADMIN)
                        .requestMatchers(HttpMethod.POST, "/bookings", "/bookings/*/payment", "/bookings/*/cancel")
                        .hasRole(StayHubJwtValidator.ROLE_USER)
                        .requestMatchers(HttpMethod.GET, "/bookings", "/bookings/*")
                        .hasAnyRole(StayHubJwtValidator.ROLE_USER, StayHubJwtValidator.ROLE_ADMIN)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
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
                JwtValidators.createDefaultWithIssuer(issuer),
                new StayHubJwtValidator()));
        return decoder;
    }

    /**
     * Converter mặc định tự điền iat = exp - 1s khi token không có iat, làm validator không phát hiện được.
     * JWT nội bộ bắt buộc có iat nên phải gỡ giá trị tự điền đó.
     */
    private static Map<String, Object> convertClaimsKeepingMissingIat(Map<String, Object> claims) {
        Map<String, Object> converted = new HashMap<>(DEFAULT_CLAIM_CONVERTER.convert(claims));
        if (!claims.containsKey(JwtClaimNames.IAT)) {
            converted.remove(JwtClaimNames.IAT);
        }
        return converted;
    }

    /** Ánh xạ claim role thành ROLE_USER hoặc ROLE_ADMIN. */
    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(StayHubJwtValidator.CLAIM_ROLE);
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
