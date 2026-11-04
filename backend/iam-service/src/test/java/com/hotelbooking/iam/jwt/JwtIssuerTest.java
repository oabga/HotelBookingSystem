package com.hotelbooking.iam.jwt;

import com.hotelbooking.iam.domain.AppUser;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JwtIssuerTest {

    private final JwtKeyProvider keyProvider = new JwtKeyProvider();
    private final JwtIssuer issuer = new JwtIssuer(keyProvider, "http://localhost:8084", 15);

    @Test
    void issuedTokenVerifiesAgainstThePublishedPublicKeyAndExpiresIn15Minutes() throws Exception {
        AppUser user = appUser(5L, 2L, "admin-tenant2@example.com", "ADMIN");

        String token = issuer.issue(user);
        SignedJWT jwt = SignedJWT.parse(token);

        assertThat(jwt.verify(new RSASSAVerifier(keyProvider.signingKey().toPublicJWK()))).isTrue();
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(keyProvider.signingKey().getKeyID());

        Instant iat = jwt.getJWTClaimsSet().getIssueTime().toInstant();
        Instant exp = jwt.getJWTClaimsSet().getExpirationTime().toInstant();
        assertThat(exp).isEqualTo(iat.plusSeconds(15 * 60));

        assertThat(jwt.getJWTClaimsSet().getStringClaim("role")).isEqualTo("ADMIN");
        assertThat(jwt.getJWTClaimsSet().getLongClaim("tenant_id")).isEqualTo(2L);
    }

    private static AppUser appUser(long id, long tenantId, String email, String role) {
        try {
            Constructor<AppUser> ctor = AppUser.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            AppUser user = ctor.newInstance();
            ReflectionTestUtils.setField(user, "id", id);
            ReflectionTestUtils.setField(user, "tenantId", tenantId);
            ReflectionTestUtils.setField(user, "email", email);
            ReflectionTestUtils.setField(user, "role", role);
            return user;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
