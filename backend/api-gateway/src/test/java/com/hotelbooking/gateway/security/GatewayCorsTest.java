package com.hotelbooking.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Browser chỉ đọc được status khi response có CORS header, kể cả 401 do Spring Security trả về. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayCorsTest {
    private static final String APP_1 = "http://localhost:3000";
    private static final String APP_2 = "http://localhost:3001";

    @Autowired
    WebTestClient client;

    @Test
    void unauthorizedResponseIsReadableByAllowedOrigins() {
        for (String origin : new String[] {APP_1, APP_2}) {
            client.get().uri("/api/hotels")
                    .header(HttpHeaders.ORIGIN, origin)
                    .exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        }
    }

    @Test
    void invalidTokenResponseIsReadableByAllowedOrigin() {
        client.get().uri("/api/bookings")
                .header(HttpHeaders.ORIGIN, APP_1)
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, APP_1);
    }

    @Test
    void unauthorizedResponseGivesNoCorsAccessToOtherOrigins() {
        client.get().uri("/api/hotels")
                .header(HttpHeaders.ORIGIN, "http://evil.test")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    void preflightAllowsPatchForSupportStatus() {
        client.method(HttpMethod.OPTIONS).uri("/api/support/tickets/1/status")
                .header(HttpHeaders.ORIGIN, APP_1)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, APP_1);
    }

    @Test
    void preflightNeedsNoTokenAndRejectsOtherOrigins() {
        client.method(HttpMethod.OPTIONS).uri("/api/bookings")
                .header(HttpHeaders.ORIGIN, APP_1)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, APP_1);

        client.method(HttpMethod.OPTIONS).uri("/api/bookings")
                .header(HttpHeaders.ORIGIN, "http://evil.test")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }
}
