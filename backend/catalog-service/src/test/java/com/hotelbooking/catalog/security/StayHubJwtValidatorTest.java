package com.hotelbooking.catalog.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StayHubJwtValidatorTest {
    private final StayHubJwtValidator validator = new StayHubJwtValidator();

    @Test
    void acceptsRequiredClaimsWithNumericTenantId() {
        assertThat(validator.validate(jwt(12L)).hasErrors()).isFalse();
    }

    @Test
    void rejectsStringTenantIdAndUnsupportedRole() {
        assertThat(validator.validate(jwt("12")).hasErrors()).isTrue();
        assertThat(validator.validate(jwtWithRole(12L, "MANAGER")).hasErrors()).isTrue();
    }

    private static Jwt jwt(Object tenantId) {
        return jwtWithRole(tenantId, "USER");
    }

    private static Jwt jwtWithRole(Object tenantId, String role) {
        Instant now = Instant.parse("2026-11-03T12:00:00Z");
        return new Jwt("test-token", now, now.plusSeconds(60), Map.of("alg", "RS256"), Map.of(
                "sub", "user-1", "email", "user@example.com", "tenant_id", tenantId,
                "role", role, "iss", "http://localhost:8084"));
    }
}
