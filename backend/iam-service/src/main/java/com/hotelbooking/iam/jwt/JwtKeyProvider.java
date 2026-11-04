package com.hotelbooking.iam.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.springframework.stereotype.Component;

/**
 * Generates a single RSA keypair for the lifetime of this IAM instance. Restarting IAM rotates
 * the key, invalidating JWTs minted before the restart (see docs/api-contract.md §3.3 — no key
 * persistence/rotation in this demo).
 */
@Component
public class JwtKeyProvider {

    private final RSAKey signingKey;

    public JwtKeyProvider() {
        try {
            this.signingKey = new RSAKeyGenerator(2048)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID("stayhub-key-1")
                    .generate();
        } catch (JOSEException ex) {
            throw new IllegalStateException("Unable to generate IAM signing key", ex);
        }
    }

    public RSAKey signingKey() {
        return signingKey;
    }

    public JWKSet publicJwkSet() {
        return new JWKSet(signingKey.toPublicJWK());
    }
}
