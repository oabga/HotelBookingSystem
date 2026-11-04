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

    private final AppUserRepository appUserRepository;
    private final JwtIssuer jwtIssuer;
    private final SsoProperties ssoProperties;

    public SsoLoginService(AppUserRepository appUserRepository, JwtIssuer jwtIssuer, SsoProperties ssoProperties) {
        this.appUserRepository = appUserRepository;
        this.jwtIssuer = jwtIssuer;
        this.ssoProperties = ssoProperties;
    }

    /**
     * Validates the Google identity against the provisioned {@code app_user}, binds/checks
     * {@code google_sub}, mints a JWT and returns the full redirect URL for the given app
     * (callback base URL + {@code #token=...}).
     *
     * @throws NotProvisionedException when the login must not result in a JWT (403 at IAM)
     */
    public String completeLogin(OidcUser oidcUser, String app) {
        if (!ssoProperties.isAllowed(app)) {
            throw new NotProvisionedException();
        }
        String token = issueToken(oidcUser);
        return ssoProperties.callbackFor(app) + "#token=" + token;
    }

    @Transactional
    public String issueToken(OidcUser oidcUser) {
        String email = oidcUser.getEmail();
        Boolean emailVerified = oidcUser.getEmailVerified();
        if (email == null || email.isBlank() || emailVerified == null || !emailVerified) {
            throw new NotProvisionedException();
        }
        String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
        AppUser user = appUserRepository.findByEmail(normalizedEmail).orElseThrow(NotProvisionedException::new);
        bindOrVerifyGoogleSub(user, oidcUser.getSubject());
        return jwtIssuer.issue(user);
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
