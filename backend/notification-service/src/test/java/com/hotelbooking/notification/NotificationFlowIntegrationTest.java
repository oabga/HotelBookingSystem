package com.hotelbooking.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelbooking.notification.service.NotificationDelivery;
import com.hotelbooking.notification.testing.TestJwtIssuer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.hotelbooking.notification.testing.TestJwtIssuer.TENANT_A;
import static com.hotelbooking.notification.testing.TestJwtIssuer.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;

/** Chạy trên PostgreSQL thật với migration thật của các service. Job được gọi tay để biết chắc thời điểm chạy. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class NotificationFlowIntegrationTest {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    private static final String USER_A1 = "user-a1@example.test";
    private static final String USER_A2 = "user-a2@example.test";
    private static final String USER_B1 = "user-b1@example.test";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TestJwtIssuer IAM = new TestJwtIssuer();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withDatabaseName("hotelbooking");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            for (String script : List.of(
                    "../iam-service/src/main/resources/db/migration/V1__create_tenant_and_app_user.sql",
                    "../iam-service/src/main/resources/db/migration/V2__seed_tenants.sql",
                    "../catalog-service/src/main/resources/db/migration/V1__create_room.sql",
                    "../catalog-service/src/main/resources/db/migration/V2__seed_rooms.sql",
                    "../booking-service/src/main/resources/db/migration/V1__create_booking.sql",
                    "../booking-service/src/main/resources/db/migration/V2__create_booking_reschedule.sql")) {
                ScriptUtils.executeSqlScript(connection,
                        new EncodedResource(new FileSystemResource(script), StandardCharsets.UTF_8));
            }
        }
        // Migration của Support có khối $$ mà ScriptUtils không tách đúng, nên chạy bằng Flyway như lúc thật.
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:../support-service/src/main/resources/db/migration")
                .table("flyway_schema_history_support")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("security.jwt.issuer", () -> TestJwtIssuer.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", IAM::jwkSetUri);
        // Test tự gọi job.
        registry.add("notification.delivery.interval", () -> "PT1H");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NotificationDelivery delivery;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetData() {
        jdbc.execute("TRUNCATE notification, notification_outbox RESTART IDENTITY");
        jdbc.update("DELETE FROM app_user");
        String insert = "INSERT INTO app_user (email, tenant_id, role) VALUES (?, ?, ?)";
        jdbc.update(insert, USER_A1, TENANT_A, "USER");
        jdbc.update(insert, USER_A2, TENANT_A, "USER");
        jdbc.update(insert, USER_B1, TENANT_B, "USER");
    }

    private record Reply(int status, JsonNode body) {
    }

    private static String user(String email, long tenant) {
        return IAM.token("USER", tenant, email);
    }

    private long userId(String email) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);
    }

    private long event(String email, long tenant, String title) {
        return jdbc.queryForObject("""
                INSERT INTO notification_outbox (tenant_id, recipient_user_id, type, title, body, reference_id)
                VALUES (?, ?, 'DATE_CHANGE_APPROVED', ?, 'Nội dung', 7) RETURNING id
                """, Long.class, tenant, userId(email), title);
    }

    private Reply send(String method, String path, String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(),
                response.body().isEmpty() ? JSON.nullNode() : JSON.readTree(response.body()));
    }

    @Test
    void usesItsOwnFlywayHistory() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history_notification WHERE success AND version = '1'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void eventBecomesNotificationForItsRecipientOnly() throws Exception {
        event(USER_A1, TENANT_A, "Đã duyệt");

        assertThat(delivery.deliverPending()).isEqualTo(1);

        Reply mine = send("GET", "/notifications", user(USER_A1, TENANT_A));
        assertThat(mine.status()).isEqualTo(200);
        assertThat(mine.body().get("unreadCount").asLong()).isEqualTo(1);
        JsonNode item = mine.body().get("items").get(0);
        assertThat(item.get("title").asText()).isEqualTo("Đã duyệt");
        assertThat(item.get("type").asText()).isEqualTo("DATE_CHANGE_APPROVED");
        assertThat(item.get("referenceId").asLong()).isEqualTo(7);
        assertThat(item.get("readAt").isNull()).isTrue();
        assertThat(item.get("createdAt").asText()).endsWith("Z");

        assertThat(send("GET", "/notifications", user(USER_A2, TENANT_A)).body().get("items")).isEmpty();
        assertThat(send("GET", "/notifications", user(USER_B1, TENANT_B)).body().get("items")).isEmpty();
    }

    @Test
    void eventIsDeliveredOnceEvenIfProcessedAgain() {
        long id = event(USER_A1, TENANT_A, "Đã duyệt");
        delivery.deliverPending();
        assertThat(delivery.deliverPending()).isZero();

        jdbc.update("UPDATE notification_outbox SET processed_at = NULL WHERE id = ?", id);
        assertThat(delivery.deliverPending()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification", Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentJobsDoNotDuplicate() throws Exception {
        for (int i = 0; i < 20; i++) {
            event(USER_A1, TENANT_A, "Sự kiện " + i);
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(delivery::deliverPending));
            }
            int total = 0;
            for (Future<Integer> future : futures) {
                total += future.get();
            }
            assertThat(total).isEqualTo(20);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification", Long.class)).isEqualTo(20);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE processed_at IS NULL", Long.class)).isZero();
    }

    @Test
    void newestFirstAndPaginated() throws Exception {
        event(USER_A1, TENANT_A, "Cũ");
        event(USER_A1, TENANT_A, "Mới");
        delivery.deliverPending();

        Reply page = send("GET", "/notifications?page=0&size=1", user(USER_A1, TENANT_A));
        assertThat(page.body().get("items").get(0).get("title").asText()).isEqualTo("Mới");
        assertThat(page.body().get("totalElements").asLong()).isEqualTo(2);
        assertThat(page.body().get("totalPages").asInt()).isEqualTo(2);
        assertThat(page.body().get("unreadCount").asLong()).isEqualTo(2);
    }

    @Test
    void invalidPagingIs400() throws Exception {
        for (String query : List.of("page=-1", "size=0", "size=101", "page=abc")) {
            assertThat(send("GET", "/notifications?" + query, user(USER_A1, TENANT_A)).status()).as(query).isEqualTo(400);
        }
    }

    @Test
    void markReadLowersUnreadCountAndKeepsFirstReadTime() throws Exception {
        event(USER_A1, TENANT_A, "Đã duyệt");
        delivery.deliverPending();
        long id = send("GET", "/notifications", user(USER_A1, TENANT_A)).body().get("items").get(0).get("id").asLong();

        Reply read = send("POST", "/notifications/" + id + "/read", user(USER_A1, TENANT_A));
        assertThat(read.status()).isEqualTo(200);
        String firstRead = read.body().get("readAt").asText();
        assertThat(firstRead).endsWith("Z");
        assertThat(send("GET", "/notifications", user(USER_A1, TENANT_A)).body().get("unreadCount").asLong()).isZero();

        jdbc.update("UPDATE notification SET read_at = read_at - interval '1 hour' WHERE id = ?", id);
        String earlier = send("GET", "/notifications", user(USER_A1, TENANT_A))
                .body().get("items").get(0).get("readAt").asText();
        assertThat(send("POST", "/notifications/" + id + "/read", user(USER_A1, TENANT_A)).body().get("readAt").asText())
                .isEqualTo(earlier);
    }

    @Test
    void otherPeoplesNotificationsLookLikeMissingOnes() throws Exception {
        event(USER_A1, TENANT_A, "Đã duyệt");
        delivery.deliverPending();
        long id = send("GET", "/notifications", user(USER_A1, TENANT_A)).body().get("items").get(0).get("id").asLong();

        Reply missing = send("POST", "/notifications/99999/read", user(USER_A2, TENANT_A));
        Reply sameTenant = send("POST", "/notifications/" + id + "/read", user(USER_A2, TENANT_A));
        Reply otherTenant = send("POST", "/notifications/" + id + "/read", user(USER_B1, TENANT_B));

        assertThat(List.of(missing.status(), sameTenant.status(), otherTenant.status())).containsOnly(404);
        assertThat(sameTenant.body().get("detail")).isEqualTo(missing.body().get("detail"));
        assertThat(jdbc.queryForObject("SELECT read_at IS NULL FROM notification WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void userNotInAppUserOrWithChangedRoleIs403() throws Exception {
        assertThat(send("GET", "/notifications", user("ghost@example.test", TENANT_A)).status()).isEqualTo(403);
        assertThat(send("GET", "/notifications", IAM.token("ADMIN", TENANT_A, USER_A1)).status()).isEqualTo(403);
        assertThat(send("GET", "/notifications", user(USER_A1, TENANT_B)).status()).isEqualTo(403);
    }

    @Test
    void requestsWithoutValidTokenAre401() throws Exception {
        assertThat(send("GET", "/notifications", "not-a-jwt").status()).isEqualTo(401);
        String wrongIssuer = IAM.sign(IAM.validClaims("USER", TENANT_A, USER_A1).issuer("http://evil.test").build());
        assertThat(send("GET", "/notifications", wrongIssuer).status()).isEqualTo(401);
    }

    @Test
    void thereIsNoApiToCreateNotifications() throws Exception {
        assertThat(send("POST", "/notifications", IAM.token("ADMIN", TENANT_A, USER_A1)).status()).isIn(403, 405);
    }
}
