package com.hotelbooking.catalog.security;

import org.springframework.security.oauth2.jwt.Jwt;

public record CurrentUser(long tenantId, boolean admin) {
    public static CurrentUser from(Jwt jwt) {
        return new CurrentUser(
                jwt.getClaim(StayHubJwtValidator.CLAIM_TENANT_ID),
                StayHubJwtValidator.ROLE_ADMIN.equals(jwt.getClaimAsString(StayHubJwtValidator.CLAIM_ROLE)));
    }
}
