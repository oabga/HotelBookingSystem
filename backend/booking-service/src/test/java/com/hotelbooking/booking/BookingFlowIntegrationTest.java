package com.hotelbooking.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelbooking.booking.support.TestJwtIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static com.hotelbooking.booking.support.TestJwtIssuer.TENANT_A;
import static com.hotelbooking.booking.support.TestJwtIssuer.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chạy service thật với PostgreSQL thật: Flyway, ddl-auto=validate, JWT qua JWKS và các ràng buộc của bảng booking.
 * Bảng tenant, app_user và room được tạo từ đúng migration của IAM và Catalog, theo thứ tự A -> B -> C.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class BookingFlowIntegrationTest {

    static {
        // Kết nối chạy migration của A và B mở trước khi BookingServiceApplication đặt UTC (xem class đó).
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    /** ID phòng theo thứ tự seed trong catalog-service V2__seed_rooms.sql. */
    private static final long ROOM_A_101 = 1;
    private static final long ROOM_A_102 = 2;
    private static final long ROOM_B_201 = 3;
    private static final long ROOM_A_RACE = 5;

    private static final String USER_A1 = "user-a1@example.test";
    private static final String USER_A2 = "user-a2@example.test";
    private static final String ADMIN_A = "admin-a@example.test";
    private static final String USER_B1 = "user-b1@example.test";
    private static final String ADMIN_B = "admin-b@example.test";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TestJwtIssuer IAM = new TestJwtIssuer();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withDatabaseName("hotelbooking");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws SQLException {
        applyUpstreamMigrations();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("security.jwt.issuer", () -> TestJwtIssuer.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", IAM::jwkSetUri);
    }

    /** Spring cần schema của A và B trước khi Flyway của booking chạy. Gradle chạy test từ thư mục booking-service. */
    private static void applyUpstreamMigrations() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            for (String script : List.of(
                    "../iam-service/src/main/resources/db/migration/V1__create_tenant_and_app_user.sql",
                    "../iam-service/src/main/resources/db/migration/V2__seed_tenants.sql",
                    "../catalog-service/src/main/resources/db/migration/V1__create_room.sql",
                    "../catalog-service/src/main/resources/db/migration/V2__seed_rooms.sql")) {
                ScriptUtils.executeSqlScript(connection,
                        new EncodedResource(new FileSystemResource(script), StandardCharsets.UTF_8));
            }
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM booking");
        jdbc.update("DELETE FROM app_user");
        String insert = "INSERT INTO app_user (email, tenant_id, role) VALUES (?, ?, ?)";
        jdbc.update(insert, USER_A1, TENANT_A, "USER");
        jdbc.update(insert, USER_A2, TENANT_A, "USER");
        jdbc.update(insert, ADMIN_A, TENANT_A, "ADMIN");
        jdbc.update(insert, USER_B1, TENANT_B, "USER");
        jdbc.update(insert, ADMIN_B, TENANT_B, "ADMIN");
    }

    @Test
    void flywayUsesOwnHistoryTableOnSharedDatabase() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history_booking WHERE version = '1' AND success", Integer.class);

        assertThat(applied).isEqualTo(1);
    }

    @Test
    void happyPath_storesConfirmedRowAndReturnsUtc() throws Exception {
        HttpResponse<String> response = book(ROOM_A_101, "2026-12-01T14:00:00+07:00", "2026-12-02T11:00:00+07:00", userA1());

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(body.get("tenantId").asLong()).isEqualTo(TENANT_A);
        assertThat(body.get("userEmail").asText()).isEqualTo(USER_A1);
        assertThat(body.get("checkIn").asText()).isEqualTo("2026-12-01T07:00:00Z");
        assertThat(body.get("checkOut").asText()).isEqualTo("2026-12-02T04:00:00Z");
        assertThat(body.get("createdAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
        assertThat(bookingCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT check_in = TIMESTAMPTZ '2026-12-01 07:00:00+00' FROM booking", Boolean.class)).isTrue();
    }

    @Test
    void anyOverlapOnSameRoom_returns409WithoutSecondRow() throws Exception {
        assertThat(book(ROOM_A_101, "2026-12-10T00:00:00Z", "2026-12-12T00:00:00Z", userA1()).statusCode())
                .isEqualTo(201);

        // Trùng hẳn, đè đầu, đè cuối, nằm trong, bao ngoài
        assertThat(book(ROOM_A_101, "2026-12-10T00:00:00Z", "2026-12-12T00:00:00Z", userA2()).statusCode()).isEqualTo(409);
        assertThat(book(ROOM_A_101, "2026-12-09T00:00:00Z", "2026-12-10T00:00:01Z", userA2()).statusCode()).isEqualTo(409);
        assertThat(book(ROOM_A_101, "2026-12-11T23:59:59Z", "2026-12-13T00:00:00Z", userA2()).statusCode()).isEqualTo(409);
        assertThat(book(ROOM_A_101, "2026-12-10T12:00:00Z", "2026-12-11T00:00:00Z", userA2()).statusCode()).isEqualTo(409);
        assertThat(book(ROOM_A_101, "2026-12-09T00:00:00Z", "2026-12-13T00:00:00Z", userA2()).statusCode()).isEqualTo(409);
        assertThat(bookingCount()).isEqualTo(1);
    }

    @Test
    void sameRoomAtDifferentTimes_isAllowed() throws Exception {
        assertThat(book(ROOM_A_101, "2026-12-10T00:00:00Z", "2026-12-12T00:00:00Z", userA1()).statusCode())
                .isEqualTo(201);

        // Khoảng thời gian là [check_in, check_out): trả phòng đúng lúc người sau nhận phòng thì không đè nhau.
        assertThat(book(ROOM_A_101, "2026-12-12T00:00:00Z", "2026-12-13T00:00:00Z", userA2()).statusCode())
                .isEqualTo(201);
        assertThat(book(ROOM_A_101, "2026-12-01T00:00:00Z", "2026-12-02T00:00:00Z", userA2()).statusCode())
                .isEqualTo(201);
        assertThat(bookingCount()).isEqualTo(3);
    }

    @Test
    void sameTimeOnDifferentRoom_isAllowed() throws Exception {
        assertThat(book(ROOM_A_101, "2026-12-10T00:00:00Z", "2026-12-12T00:00:00Z", userA1()).statusCode())
                .isEqualTo(201);
        assertThat(book(ROOM_A_102, "2026-12-10T00:00:00Z", "2026-12-12T00:00:00Z", userA1()).statusCode())
                .isEqualTo(201);
    }

    /** Hai dòng trong bảng ở docs/erd-v2.md §2. */
    @Test
    void consecutiveStaysFollowActualHours() throws Exception {
        assertThat(book(ROOM_A_101, "2026-12-02T12:00:00+07:00", "2026-12-03T14:00:00+07:00", userA1()).statusCode())
                .isEqualTo(201);
        assertThat(book(ROOM_A_101, "2026-12-03T12:00:00+07:00", "2026-12-04T14:00:00+07:00", userA2()).statusCode())
                .isEqualTo(409);

        assertThat(book(ROOM_A_102, "2026-12-02T14:00:00+07:00", "2026-12-03T11:00:00+07:00", userA1()).statusCode())
                .isEqualTo(201);
        assertThat(book(ROOM_A_102, "2026-12-03T14:00:00+07:00", "2026-12-04T11:00:00+07:00", userA2()).statusCode())
                .isEqualTo(201);
    }

    @Test
    void roomOfAnotherTenantOrMissingRoom_returnsSame404WithoutRow() throws Exception {
        HttpResponse<String> otherTenant = book(ROOM_B_201, "2026-12-10T00:00:00Z", "2026-12-11T00:00:00Z", userA1());
        HttpResponse<String> missing = book(999_999, "2026-12-10T00:00:00Z", "2026-12-11T00:00:00Z", userA1());

        assertThat(otherTenant.statusCode()).isEqualTo(404);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(detail(otherTenant)).isEqualTo(detail(missing));
        assertThat(bookingCount()).isZero();
    }

    /** EXCLUDE chỉ xét room_id và chạy trước FK, nên không được để 409 tiết lộ lịch phòng của tenant khác. */
    @Test
    void bookedRoomOfAnotherTenant_returnsSame404AsMissingRoom() throws Exception {
        String tenantBUser = IAM.token("USER", TENANT_B, USER_B1);
        assertThat(book(ROOM_B_201, "2026-12-10T00:00:00Z", "2026-12-11T00:00:00Z", tenantBUser).statusCode())
                .isEqualTo(201);

        HttpResponse<String> crossTenant = book(ROOM_B_201, "2026-12-10T00:00:00Z", "2026-12-11T00:00:00Z", userA1());
        HttpResponse<String> missing = book(999_999, "2026-12-10T00:00:00Z", "2026-12-11T00:00:00Z", userA1());

        assertThat(crossTenant.statusCode()).isEqualTo(404);
        assertThat(detail(crossTenant)).isEqualTo(detail(missing));
        assertThat(bookingCount()).isEqualTo(1);
    }

    @Test
    void tenantMissingFromDatabase_returns403WithoutRow() throws Exception {
        String unknownTenant = IAM.token("USER", 999L, USER_A1);

        assertThat(book(ROOM_A_101, "2026-12-10T00:00:00Z", "2026-12-11T00:00:00Z", unknownTenant).statusCode())
                .isEqualTo(403);
        assertThat(bookingCount()).isZero();
    }

    @Test
    void uppercaseEmailInJwt_matchesLowercaseAppUser() throws Exception {
        String token = IAM.token("USER", TENANT_A, "User-A1@Example.TEST");

        HttpResponse<String> response = book(ROOM_A_101, "2026-12-10T00:00:00Z", "2026-12-11T00:00:00Z", token);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(JSON.readTree(response.body()).get("userEmail").asText()).isEqualTo(USER_A1);
        assertThat(ids(get("/bookings", userA1()))).hasSize(1);
    }

    @Test
    void userMissingFromTenant_returns403WithoutRow() throws Exception {
        String notProvisioned = IAM.token("USER", TENANT_A, "ghost@example.test");
        String wrongTenant = IAM.token("USER", TENANT_A, USER_B1);

        assertThat(book(ROOM_A_101, "2026-12-10T00:00:00Z", "2026-12-11T00:00:00Z", notProvisioned).statusCode())
                .isEqualTo(403);
        assertThat(book(ROOM_A_101, "2026-12-10T00:00:00Z", "2026-12-11T00:00:00Z", wrongTenant).statusCode())
                .isEqualTo(403);
        assertThat(bookingCount()).isZero();
    }

    /** Lặp lại vì kết quả tranh chấp phụ thuộc thời điểm; có lần PostgreSQL báo deadlock thay vì lỗi EXCLUDE. */
    @RepeatedTest(5)
    void concurrentRequestsForSameTime_exactlyOneWins() throws Exception {
        int requests = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            String token = i % 2 == 0 ? userA1() : userA2();
            calls.add(() -> {
                start.await();
                return book(ROOM_A_RACE, "2026-12-20T00:00:00Z", "2026-12-21T00:00:00Z", token).statusCode();
            });
        }

        try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
            List<Future<Integer>> results = calls.stream().map(pool::submit).toList();
            start.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
        }
        assertThat(bookingCount()).isEqualTo(1);
    }

    @Test
    void readsAreIsolatedByOwnerRoleAndTenant() throws Exception {
        long a1Booking = createdId(book(ROOM_A_101, "2026-12-10T00:00:00Z", "2026-12-11T00:00:00Z", userA1()));
        long a2Booking = createdId(book(ROOM_A_102, "2026-12-10T00:00:00Z", "2026-12-11T00:00:00Z", userA2()));

        assertThat(ids(get("/bookings", userA1()))).containsExactly(a1Booking);
        assertThat(ids(get("/bookings", IAM.token("ADMIN", TENANT_A, ADMIN_A)))).containsExactly(a1Booking, a2Booking);
        assertThat(ids(get("/bookings", IAM.token("USER", TENANT_B, USER_B1)))).isEmpty();
        assertThat(ids(get("/bookings", adminB()))).isEmpty();

        assertThat(get("/bookings/" + a1Booking, userA1()).statusCode()).isEqualTo(200);
        assertThat(get("/bookings/" + a2Booking, IAM.token("ADMIN", TENANT_A, ADMIN_A)).statusCode()).isEqualTo(200);

        HttpResponse<String> otherOwner = get("/bookings/" + a1Booking, userA2());
        HttpResponse<String> otherTenant = get("/bookings/" + a1Booking, adminB());
        HttpResponse<String> missing = get("/bookings/999999", userA1());
        assertThat(Stream.of(otherOwner, otherTenant, missing).map(HttpResponse::statusCode))
                .containsOnly(404);
        assertThat(detail(otherOwner)).isEqualTo(detail(missing));
        assertThat(detail(otherTenant)).isEqualTo(detail(missing));
    }

    private long bookingCount() {
        return jdbc.queryForObject("SELECT count(*) FROM booking", Long.class);
    }

    private HttpResponse<String> book(long roomId, String checkIn, String checkOut, String token) throws Exception {
        String body = JSON.createObjectNode()
                .put("roomId", roomId)
                .put("checkIn", checkIn)
                .put("checkOut", checkOut)
                .toString();
        HttpRequest request = HttpRequest.newBuilder(uri("/bookings"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static long createdId(HttpResponse<String> response) throws IOException {
        assertThat(response.statusCode()).isEqualTo(201);
        return JSON.readTree(response.body()).get("id").asLong();
    }

    private static List<Long> ids(HttpResponse<String> response) throws IOException {
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode array = JSON.readTree(response.body());
        return Stream.iterate(0, i -> i < array.size(), i -> i + 1)
                .map(i -> array.get(i).get("id").asLong())
                .toList();
    }

    private static String detail(HttpResponse<String> response) throws IOException {
        return JSON.readTree(response.body()).get("detail").asText();
    }

    private static String userA1() {
        return IAM.token("USER", TENANT_A, USER_A1);
    }

    private static String userA2() {
        return IAM.token("USER", TENANT_A, USER_A2);
    }

    private static String adminB() {
        return IAM.token("ADMIN", TENANT_B, ADMIN_B);
    }
}
