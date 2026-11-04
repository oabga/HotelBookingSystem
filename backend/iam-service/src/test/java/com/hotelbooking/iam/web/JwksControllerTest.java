package com.hotelbooking.iam.web;

import com.hotelbooking.iam.jwt.JwtKeyProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwksControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void jwksExposesOnlyThePublicKey() {
        JwksController controller = new JwksController(new JwtKeyProvider());

        Map<String, Object> body = controller.jwks();
        List<Map<String, Object>> keys = (List<Map<String, Object>>) body.get("keys");

        assertThat(keys).hasSize(1);
        Map<String, Object> key = keys.get(0);
        assertThat(key.get("kty")).isEqualTo("RSA");
        assertThat(key.get("alg")).isEqualTo("RS256");
        assertThat(key.get("kid")).isEqualTo("stayhub-key-1");
        assertThat(key).containsKeys("n", "e");
        assertThat(key).doesNotContainKey("d");
    }
}
