package com.hotelbooking.booking.security;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Cấp tenant cho Hibernate {@code @TenantId}: mỗi Session lấy tenant từ JWT đã xác minh của request hiện tại.
 * Hibernate tự thêm {@code tenant_id = ?} vào truy vấn JPQL/Criteria và kiểm tra tenant khi INSERT entity.
 * Truy vấn native và {@code findById} không được lọc, nên các truy vấn đó vẫn phải tự ghi điều kiện tenant.
 *
 * <ul>
 *   <li>Request có JWT: tenant trong claim {@code tenant_id}.</li>
 *   <li>Job hệ thống chạy trong {@link #runAsSystem}: tenant gốc, không lọc, để xử lý mọi tenant.</li>
 *   <li>Còn lại: {@link #NO_TENANT}. tenant_id luôn dương nên không đọc được dòng nào và khóa ngoại chặn ghi.</li>
 * </ul>
 */
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<Long>, HibernatePropertiesCustomizer {

    static final Long ROOT_TENANT = -1L;
    static final Long NO_TENANT = 0L;

    private static final ThreadLocal<Boolean> SYSTEM = ThreadLocal.withInitial(() -> false);

    /** Chạy công việc của hệ thống trên mọi tenant. Session phải được mở bên trong khối này. */
    public static <T> T runAsSystem(Supplier<T> work) {
        boolean previous = SYSTEM.get();
        SYSTEM.set(true);
        try {
            return work.get();
        } finally {
            SYSTEM.set(previous);
        }
    }

    public static void runAsSystem(Runnable work) {
        runAsSystem(() -> {
            work.run();
            return null;
        });
    }

    @Override
    public Long resolveCurrentTenantIdentifier() {
        if (SYSTEM.get()) {
            return ROOT_TENANT;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token
                && token.getToken().getClaims().get(StayHubJwtValidator.CLAIM_TENANT_ID) instanceof Long tenantId) {
            return tenantId;
        }
        return NO_TENANT;
    }

    @Override
    public boolean isRoot(Long tenantId) {
        return ROOT_TENANT.equals(tenantId);
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
