package com.hotelbooking.support.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Set;

/**
 * Kiểm tra các claim bắt buộc của JWT nội bộ.
 * Validator mặc định của Spring chỉ kiểm tra exp khi claim có mặt, nên token thiếu exp phải bị chặn ở đây.
 * Thiếu hoặc sai claim thì trả 401, không có tenant hay role mặc định.
 */
public class StayHubJwtValidator implements OAuth2TokenValidator<Jwt> {

    public static final String CLAIM_TENANT_ID = "tenant_id";
    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_ROLE = "role";
    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";

    private static final Set<String> ALLOWED_ROLES = Set.of(ROLE_USER, ROLE_ADMIN);

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (jwt.getExpiresAt() == null) {
            return invalid("exp is required");
        }
        if (jwt.getIssuedAt() == null) {
            return invalid("iat is required");
        }
        if (!isNonBlankString(jwt.getClaims().get("sub"))) {
            return invalid("sub is required");
        }
        if (!isNonBlankString(jwt.getClaims().get(CLAIM_EMAIL))) {
            return invalid("email is required");
        }
        if (!(jwt.getClaims().get(CLAIM_TENANT_ID) instanceof Long tenantId) || tenantId <= 0) {
            return invalid("tenant_id must be a positive integer");
        }
        if (!(jwt.getClaims().get(CLAIM_ROLE) instanceof String role) || !ALLOWED_ROLES.contains(role)) {
            return invalid("role must be USER or ADMIN");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static boolean isNonBlankString(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private static OAuth2TokenValidatorResult invalid(String description) {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
    }
}
