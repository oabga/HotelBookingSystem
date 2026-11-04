package com.hotelbooking.iam.jwt;

import com.hotelbooking.iam.domain.AppUser;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/** Mints the internal RS256 JWT described in docs/api-contract.md §3.3. */
@Component
public class JwtIssuer {

    private final JwtKeyProvider keyProvider;
    private final String issuer;
    private final Duration ttl;

    public JwtIssuer(
            JwtKeyProvider keyProvider,
            @Value("${security.jwt.issuer}") String issuer,
            @Value("${security.jwt.ttl-minutes:15}") long ttlMinutes) {
        this.keyProvider = keyProvider;
        this.issuer = issuer;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public String issue(AppUser user) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("tenant_id", user.getTenantId())
                .claim("role", user.getRole())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .build();
        return sign(claims);
    }

    private String sign(JWTClaimsSet claims) {
        try {
            RSAKey key = keyProvider.signingKey();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException("Unable to sign JWT", ex);
        }
    }
}
