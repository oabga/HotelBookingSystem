package com.hotelbooking.iam.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/** Allowlist of frontend apps and their callback URL, keyed by the {@code app} query param. */
@ConfigurationProperties(prefix = "sso")
public class SsoProperties {

    private Map<String, String> callbacks = new LinkedHashMap<>();
    private Registration registration = new Registration();

    public Map<String, String> getCallbacks() {
        return callbacks;
    }

    public void setCallbacks(Map<String, String> callbacks) {
        this.callbacks = callbacks;
    }

    public Registration getRegistration() {
        return registration;
    }

    public void setRegistration(Registration registration) {
        this.registration = registration;
    }

    public boolean isAllowed(String app) {
        return app != null && callbacks.containsKey(app);
    }

    public String callbackFor(String app) {
        return callbacks.get(app);
    }

    public static class Registration {
        private Long defaultTenantId = 1L;
        private String adminEmail = "";

        public Long getDefaultTenantId() {
            return defaultTenantId;
        }

        public void setDefaultTenantId(Long defaultTenantId) {
            this.defaultTenantId = defaultTenantId;
        }

        public String getAdminEmail() {
            return adminEmail;
        }

        public void setAdminEmail(String adminEmail) {
            this.adminEmail = adminEmail;
        }
    }
}
