package com.hotelbooking.support.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Locale;

/**
 * Danh tính lấy từ JWT đã xác minh; không bao giờ lấy từ body, query hay header tùy ý của client.
 * Email được đổi về chữ thường vì app_user chỉ lưu email chữ thường.
 */
public record CurrentUser(long tenantId, String email, boolean admin) {

    public static CurrentUser from(Jwt jwt) {
        return new CurrentUser(
                jwt.<Long>getClaim(StayHubJwtValidator.CLAIM_TENANT_ID),
                jwt.getClaimAsString(StayHubJwtValidator.CLAIM_EMAIL).toLowerCase(Locale.ROOT),
                StayHubJwtValidator.ROLE_ADMIN.equals(jwt.getClaimAsString(StayHubJwtValidator.CLAIM_ROLE)));
    }

    public String role() {
        return admin ? StayHubJwtValidator.ROLE_ADMIN : StayHubJwtValidator.ROLE_USER;
    }
}
