package com.hotelbooking.iam.security;

import com.hotelbooking.iam.config.SsoProperties;
import com.hotelbooking.iam.domain.AppUser;
import com.hotelbooking.iam.domain.AppUserRepository;
import com.hotelbooking.iam.jwt.JwtIssuer;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;

/**
 * Core SSO business logic, deliberately independent of the servlet request/response so it can be
 * unit-tested without a Google login or a running Spring context (docs/iam-google-sso-demo-plan-v2.md §5).
 */
@Service
public class SsoLoginService {

    public static final String SESSION_APP_ATTR = "sso_app";
    public static final String SESSION_REGISTER_ATTR = "sso_register";

    private final AppUserRepository appUserRepository;
    private final JwtIssuer jwtIssuer;
    private final SsoProperties ssoProperties;

    public SsoLoginService(AppUserRepository appUserRepository, JwtIssuer jwtIssuer, SsoProperties ssoProperties) {
        this.appUserRepository = appUserRepository;
        this.jwtIssuer = jwtIssuer;
        this.ssoProperties = ssoProperties;
    }

    /** Validates the identity and enforces each app's provisioning and role policy. */
    @Transactional
    public String completeLogin(OidcUser oidcUser, String app, boolean register) {
        if (!ssoProperties.isAllowed(app)) {
            throw new NotProvisionedException();
        }
        if (register && !"app1".equals(app)) {
            throw new NotProvisionedException();
        }
        String token = issueToken(oidcUser, app);
        return ssoProperties.callbackFor(app) + "#token=" + token;
    }

    /** Login-only overload retained for callers that do not expose registration. */
    public String completeLogin(OidcUser oidcUser, String app) {
        return completeLogin(oidcUser, app, false);
    }

    @Transactional
    public String issueToken(OidcUser oidcUser, String app) {
        String email = oidcUser.getEmail();
        Boolean emailVerified = oidcUser.getEmailVerified();
        if (email == null || email.isBlank() || emailVerified == null || !emailVerified) {
            throw new NotProvisionedException();
        }
        String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
        String configuredAdminEmail = normalize(ssoProperties.getRegistration().getAdminEmail());
        boolean configuredAdmin = !configuredAdminEmail.isBlank() && normalizedEmail.equals(configuredAdminEmail);
        AppUser user = appUserRepository.findByEmail(normalizedEmail).orElse(null);

        if ("app1".equals(app)) {
            // App 1 is the self-service USER portal. The configured administrator must use App 2.
            if (configuredAdmin) throw new NotProvisionedException();
            if (user == null) user = createUser(normalizedEmail, "USER");
            if (!"USER".equals(user.getRole())) throw new NotProvisionedException();
        } else if ("app2".equals(app)) {
            // Only the exact, Google-verified allowlisted identity can receive the ADMIN role.
            if (!configuredAdmin) throw new NotProvisionedException();
            if (user == null) user = createUser(normalizedEmail, "ADMIN");
            user.setRole("ADMIN");
        } else if ("app3".equals(app)) {
            // Support does not create accounts; it issues the role already stored in IAM.
            if (user == null || !("USER".equals(user.getRole()) || "ADMIN".equals(user.getRole()))) {
                throw new NotProvisionedException();
            }
        } else if (app == null) {
            // Compatibility entry point for service-level callers; existing accounts only.
            if (user == null) throw new NotProvisionedException();
        } else {
            throw new NotProvisionedException();
        }
        bindOrVerifyGoogleSub(user, oidcUser.getSubject());
        if ("app2".equals(app)) appUserRepository.save(user);
        return jwtIssuer.issue(user);
    }

    private AppUser createUser(String email, String role) {
        Long tenantId = ssoProperties.getRegistration().getDefaultTenantId();
        if (tenantId == null) throw new NotProvisionedException();
        return appUserRepository.save(new AppUser(email, tenantId, role));
    }

    /** Legacy registration switch now maps to the App 1 self-service entry point. */
    public String issueToken(OidcUser oidcUser, boolean register) {
        return issueToken(oidcUser, register ? "app1" : null);
    }

    /** Existing integrations remain login-only unless they explicitly request App 1 registration. */
    public String issueToken(OidcUser oidcUser) {
        return issueToken(oidcUser, (String) null);
    }

    private static String normalize(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    private void bindOrVerifyGoogleSub(AppUser user, String googleSub) {
        if (googleSub == null || googleSub.isBlank()) {
            throw new NotProvisionedException();
        }
        if (user.getGoogleSub() == null) {
            user.setGoogleSub(googleSub);
            appUserRepository.save(user);
        } else if (!Objects.equals(user.getGoogleSub(), googleSub)) {
            throw new NotProvisionedException();
        }
    }
}
