package com.hotelbooking.iam.web;

import com.hotelbooking.iam.jwt.JwtKeyProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Public key set used by Gateway/Catalog/Booking to verify IAM-minted JWTs (no OIDC discovery). */
@RestController
public class JwksController {

    private final JwtKeyProvider keyProvider;

    public JwksController(JwtKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return keyProvider.publicJwkSet().toJSONObject();
    }
}
