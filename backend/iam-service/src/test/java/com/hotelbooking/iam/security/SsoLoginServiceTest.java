package com.hotelbooking.iam.security;

import com.hotelbooking.iam.config.SsoProperties;
import com.hotelbooking.iam.domain.AppUser;
import com.hotelbooking.iam.domain.AppUserRepository;
import com.hotelbooking.iam.jwt.JwtIssuer;
import com.hotelbooking.iam.jwt.JwtKeyProvider;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the mandatory IAM unit tests from docs/iam-google-sso-demo-plan-v2.md §5: unprovisioned
 * email is rejected (403 at the controller layer), role is mapped verbatim into the JWT, and the
 * minted token carries tenant_id as a JSON number.
 */
@ExtendWith(MockitoExtension.class)
class SsoLoginServiceTest {

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private OidcUser oidcUser;

    private SsoLoginService service;

    @BeforeEach
    void setUp() {
        JwtIssuer jwtIssuer = new JwtIssuer(new JwtKeyProvider(), "http://localhost:8084", 15);
        SsoProperties ssoProperties = new SsoProperties();
        ssoProperties.setCallbacks(java.util.Map.of(
                "app1", "http://localhost:3000/callback/",
                "app2", "http://localhost:3001/callback/"));
        service = new SsoLoginService(appUserRepository, jwtIssuer, ssoProperties);
    }

    @Test
    void rejectsUnverifiedEmailWithoutQueryingTheRepository() {
        when(oidcUser.getEmail()).thenReturn("user-tenant1@example.com");
        when(oidcUser.getEmailVerified()).thenReturn(false);

        assertThatThrownBy(() -> service.issueToken(oidcUser)).isInstanceOf(NotProvisionedException.class);
        verify(appUserRepository, never()).findByEmail(any());
    }

    @Test
    void rejectsEmailNotProvisioned() {
        when(oidcUser.getEmail()).thenReturn("Unknown@Example.com");
        when(oidcUser.getEmailVerified()).thenReturn(true);
        when(appUserRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueToken(oidcUser)).isInstanceOf(NotProvisionedException.class);
    }

    @Test
    void rejectsMismatchedGoogleSubWithoutRebinding() {
        AppUser user = appUser(1L, 1L, "admin-tenant1@example.com", "USER", "sub-original");
        when(oidcUser.getEmail()).thenReturn("admin-tenant1@example.com");
        when(oidcUser.getEmailVerified()).thenReturn(true);
        when(oidcUser.getSubject()).thenReturn("sub-attacker");
        when(appUserRepository.findByEmail("admin-tenant1@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.issueToken(oidcUser)).isInstanceOf(NotProvisionedException.class);
        verify(appUserRepository, never()).save(any());
    }

    @Test
    void mintsTokenAndBindsGoogleSubOnFirstLogin() throws Exception {
        AppUser user = appUser(2L, 1L, "user-tenant1@example.com", "USER", null);
        when(oidcUser.getEmail()).thenReturn("user-tenant1@example.com");
        when(oidcUser.getEmailVerified()).thenReturn(true);
        when(oidcUser.getSubject()).thenReturn("sub-123");
        when(appUserRepository.findByEmail("user-tenant1@example.com")).thenReturn(Optional.of(user));

        String token = service.issueToken(oidcUser);

        assertThat(user.getGoogleSub()).isEqualTo("sub-123");
        verify(appUserRepository).save(user);

        SignedJWT jwt = SignedJWT.parse(token);
        assertThat(jwt.getJWTClaimsSet().getStringClaim("email")).isEqualTo("user-tenant1@example.com");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("role")).isEqualTo("USER");
        assertThat(jwt.getJWTClaimsSet().getLongClaim("tenant_id")).isEqualTo(1L);
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("2");
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("http://localhost:8084");
    }

    @Test
    void rejectsUnknownApp() {
        lenient().when(oidcUser.getEmail()).thenReturn("user-tenant1@example.com");
        lenient().when(oidcUser.getEmailVerified()).thenReturn(true);

        assertThatThrownBy(() -> service.completeLogin(oidcUser, "app3"))
                .isInstanceOf(NotProvisionedException.class);
    }

    private static AppUser appUser(long id, long tenantId, String email, String role, String googleSub) {
        try {
            Constructor<AppUser> ctor = AppUser.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            AppUser user = ctor.newInstance();
            ReflectionTestUtils.setField(user, "id", id);
            ReflectionTestUtils.setField(user, "tenantId", tenantId);
            ReflectionTestUtils.setField(user, "email", email);
            ReflectionTestUtils.setField(user, "role", role);
            ReflectionTestUtils.setField(user, "googleSub", googleSub);
            return user;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
