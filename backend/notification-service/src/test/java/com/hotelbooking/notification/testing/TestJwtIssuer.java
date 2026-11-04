package com.hotelbooking.notification.testing;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Đóng vai IAM trong test: ký JWT RS256 thật và phát public key qua JWKS HTTP thật,
 * để request đi qua đúng JwtDecoder và validator của service.
 */
public final class TestJwtIssuer {

    public static final String ISSUER = "http://localhost:8084";
    public static final long TENANT_A = 1L;
    public static final long TENANT_B = 2L;

    private final RSAKey signingKey = generateKey("stayhub-key-1");
    private final HttpServer jwksServer;

    public TestJwtIssuer() {
        try {
            jwksServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        byte[] jwks = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        jwksServer.createContext("/.well-known/jwks.json", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            exchange.getResponseBody().write(jwks);
            exchange.close();
        });
        jwksServer.start();
    }

    public String jwkSetUri() {
        return "http://localhost:" + jwksServer.getAddress().getPort() + "/.well-known/jwks.json";
    }

    /** Bộ claim hợp lệ; test sửa từng claim để tạo token lỗi. */
    public JWTClaimsSet.Builder validClaims(String role, long tenantId, String email) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("sub-" + email)
                .claim("email", email)
                .claim("tenant_id", tenantId)
                .claim("role", role)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(15, ChronoUnit.MINUTES)));
    }

    public String token(String role, long tenantId, String email) {
        return sign(validClaims(role, tenantId, email).build());
    }

    public String sign(JWTClaimsSet claims) {
        return signWith(signingKey, claims);
    }

    /** Token có cấu trúc đúng nhưng ký bằng khóa không có trong JWKS. */
    public String signWithUntrustedKey(JWTClaimsSet claims) {
        return signWith(generateKey("stayhub-key-1"), claims);
    }

    private static String signWith(RSAKey key, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static RSAKey generateKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
