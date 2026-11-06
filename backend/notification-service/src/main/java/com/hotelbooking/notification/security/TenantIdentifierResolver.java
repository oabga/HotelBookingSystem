package com.hotelbooking.notification.security;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Cấp tenant cho Hibernate {@code @TenantId}: mỗi Session lấy tenant từ JWT đã xác minh của request hiện tại.
 * Hibernate tự thêm {@code tenant_id = ?} vào truy vấn JPQL/Criteria và kiểm tra tenant khi INSERT entity.
 * Truy vấn native và {@code findById} không được lọc, nên các truy vấn đó vẫn phải tự ghi điều kiện tenant.
 * Không có JWT thì dùng {@link #NO_TENANT}: tenant_id luôn dương nên không đọc được dòng nào và khóa ngoại chặn ghi.
 * Job đọc outbox chạy với NO_TENANT nhưng chỉ dùng SQL native, nên vẫn xử lý được mọi tenant.
 */
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<Long>, HibernatePropertiesCustomizer {

    static final Long NO_TENANT = 0L;

    @Override
    public Long resolveCurrentTenantIdentifier() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token
                && token.getToken().getClaims().get(StayHubJwtValidator.CLAIM_TENANT_ID) instanceof Long tenantId) {
            return tenantId;
        }
        return NO_TENANT;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
