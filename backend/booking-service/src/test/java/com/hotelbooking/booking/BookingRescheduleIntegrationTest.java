package com.hotelbooking.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelbooking.booking.support.TestJwtIssuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.hotelbooking.booking.support.TestJwtIssuer.TENANT_A;
import static com.hotelbooking.booking.support.TestJwtIssuer.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;

/** Đổi ngày trên PostgreSQL thật. Sau mỗi test đều đối soát sold với các booking đang giữ phòng. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class BookingRescheduleIntegrationTest {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    private static final long STANDARD_A = 1; // 3 phòng, 500.000
    private static final long RACE_A = 5; // 1 phòng, 500.000
    private static final long STANDARD_B = 3;

    private static final String USER_A1 = "user-a1@example.test";
    private static final String USER_A2 = "user-a2@example.test";
    private static final String ADMIN_A = "admin-a@example.test";
    private static final String USER_B1 = "user-b1@example.test";
    private static final String ADMIN_B = "admin-b@example.test";

    private static final Instant NOW = Instant.parse("2026-11-03T02:15:30Z");

    private static final String RECONCILIATION_SQL = """
            SELECT count(*) FROM (
                SELECT i.tenant_id
                FROM room_inventory i
                LEFT JOIN booking b
                  ON b.tenant_id = i.tenant_id AND b.room_type_id = i.room_type_id
                 AND b.status IN ('PENDING_PAYMENT', 'CONFIRMED')
                 AND i.stay_date >= b.check_in AND i.stay_date < b.check_out
                GROUP BY i.tenant_id, i.room_type_id, i.stay_date, i.sold
                HAVING i.sold <> COALESCE(SUM(b.room_count), 0)
            ) AS mismatched
            """;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TestJwtIssuer IAM = new TestJwtIssuer();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withDatabaseName("hotelbooking");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws SQLException {
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
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("security.jwt.issuer", () -> TestJwtIssuer.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", IAM::jwkSetUri);
        registry.add("booking.expiry.interval", () -> "PT1H");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetData() {
        jdbc.execute("TRUNCATE booking_reschedule, booking RESTART IDENTITY");
        jdbc.update("UPDATE room_inventory SET sold = 0");
        jdbc.update("DELETE FROM app_user");
        String insert = "INSERT INTO app_user (email, tenant_id, role) VALUES (?, ?, ?)";
        jdbc.update(insert, USER_A1, TENANT_A, "USER");
        jdbc.update(insert, USER_A2, TENANT_A, "USER");
        jdbc.update(insert, ADMIN_A, TENANT_A, "ADMIN");
        jdbc.update(insert, USER_B1, TENANT_B, "USER");
        jdbc.update(insert, ADMIN_B, TENANT_B, "ADMIN");
    }

    @AfterEach
    void soldMatchesBookings() {
        assertThat(jdbc.queryForObject(RECONCILIATION_SQL, Long.class)).isZero();
    }

    private record Reply(int status, JsonNode body) {
    }

    private static String userA1() {
        return IAM.token("USER", TENANT_A, USER_A1);
    }

    private static String adminA() {
        return IAM.token("ADMIN", TENANT_A, ADMIN_A);
    }

    private Reply send(String method, String path, String token, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(),
                response.body().isEmpty() ? JSON.nullNode() : JSON.readTree(response.body()));
    }

    /** Tạo đơn qua API thật rồi thanh toán, để sold được trừ đúng như thực tế. */
    private long confirmed(long roomType, String checkIn, String checkOut, int rooms, String token) throws Exception {
        Reply created = send("POST", "/bookings", token, """
                {"roomTypeId":%d,"checkIn":"%s","checkOut":"%s","roomCount":%d}
                """.formatted(roomType, checkIn, checkOut, rooms));
        assertThat(created.status()).isEqualTo(201);
        long id = created.body().get("id").asLong();
        assertThat(send("POST", "/bookings/" + id + "/payment", token, null).status()).isEqualTo(200);
        return id;
    }

    private Reply quote(long id, String checkIn, String checkOut, String token) throws Exception {
        return send("GET", "/bookings/" + id + "/reschedule-quote?checkIn=" + checkIn + "&checkOut=" + checkOut, token, null);
    }

    private Reply reschedule(long id, long requestId, String checkIn, String checkOut, String token) throws Exception {
        return send("POST", "/bookings/" + id + "/reschedule", token, """
                {"requestId":%d,"checkIn":"%s","checkOut":"%s"}
                """.formatted(requestId, checkIn, checkOut));
    }

    private int sold(long roomType, String date) {
        return jdbc.queryForObject("SELECT sold FROM room_inventory WHERE room_type_id = ? AND stay_date = CAST(? AS DATE)",
                Integer.class, roomType, date);
    }

    // ---------- Danh sách đơn đổi được ----------

    @Test
    void reschedulableListsOnlyOwnConfirmedFutureBookingsWithNames() throws Exception {
        long mine = confirmed(STANDARD_A, "2026-12-01", "2026-12-03", 1, userA1());
        confirmed(STANDARD_A, "2026-12-05", "2026-12-06", 1, IAM.token("USER", TENANT_A, USER_A2));
        send("POST", "/bookings", userA1(), """
                {"roomTypeId":1,"checkIn":"2026-12-10","checkOut":"2026-12-11","roomCount":1}
                """);
        long started = confirmed(STANDARD_A, "2026-12-12", "2026-12-13", 1, userA1());
        jdbc.update("UPDATE room_inventory SET sold = sold - 1 WHERE room_type_id = 1 AND stay_date = '2026-12-12'");
        jdbc.update("UPDATE booking SET check_in = '2026-11-03', check_out = '2026-11-04' WHERE id = ?", started);
        jdbc.update("UPDATE room_inventory SET sold = sold + 1 WHERE room_type_id = 1 AND stay_date = '2026-11-03'");

        Reply reply = send("GET", "/bookings/reschedulable", userA1(), null);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).hasSize(1);
        JsonNode item = reply.body().get(0);
        assertThat(item.get("id").asLong()).isEqualTo(mine);
        assertThat(item.get("roomTypeName").asText()).isEqualTo("Standard");
        assertThat(item.get("hotelName").asText()).isNotBlank();
        assertThat(item.get("checkIn").asText()).isEqualTo("2026-12-01");
        assertThat(item.get("roomCount").asInt()).isEqualTo(1);
    }

    @Test
    void adminCannotListReschedulable() throws Exception {
        assertThat(send("GET", "/bookings/reschedulable", adminA(), null).status()).isEqualTo(403);
    }

    // ---------- Quote ----------

    @Test
    void quoteRecomputesTotalFromNightsAndDoesNotHoldRooms() throws Exception {
        long id = confirmed(STANDARD_A, "2026-12-01", "2026-12-03", 2, userA1());

        Reply reply = quote(id, "2026-12-10", "2026-12-13", userA1());

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body().get("available").asBoolean()).isTrue();
        assertThat(reply.body().get("nights").asInt()).isEqualTo(3);
        assertThat(reply.body().get("currentTotalAmount").decimalValue()).isEqualByComparingTo("2000000");
        assertThat(reply.body().get("newTotalAmount").decimalValue()).isEqualByComparingTo("3000000");
        assertThat(reply.body().get("roomTypeName").asText()).isEqualTo("Standard");
        assertThat(sold(STANDARD_A, "2026-12-10")).isZero();
    }

    @Test
    void quoteSaysUnavailableWhenAnyNightLacksRooms() throws Exception {
        long id = confirmed(RACE_A, "2026-12-01", "2026-12-02", 1, userA1());
        confirmed(RACE_A, "2026-12-11", "2026-12-12", 1, IAM.token("USER", TENANT_A, USER_A2));

        assertThat(quote(id, "2026-12-10", "2026-12-13", userA1()).body().get("available").asBoolean()).isFalse();
        assertThat(quote(id, "2026-12-13", "2026-12-15", userA1()).body().get("available").asBoolean()).isTrue();
    }

    /** Đơn đang giữ sẵn đêm trùng nên phòng duy nhất vẫn đủ để dời hoặc kéo dài kỳ ở. */
    @Test
    void quoteCountsOwnHeldNightsAsAvailable() throws Exception {
        long id = confirmed(RACE_A, "2026-12-01", "2026-12-03", 1, userA1());

        assertThat(quote(id, "2026-12-02", "2026-12-04", userA1()).body().get("available").asBoolean()).isTrue();
    }

    @Test
    void quoteBeyondSaleWindowIsUnavailable() throws Exception {
        long id = confirmed(STANDARD_A, "2026-12-01", "2026-12-02", 1, userA1());

        assertThat(quote(id, "2027-01-31", "2027-02-02", userA1()).body().get("available").asBoolean()).isFalse();
    }

    @Test
    void quoteRejectsBadDates() throws Exception {
        long id = confirmed(STANDARD_A, "2026-12-01", "2026-12-02", 1, userA1());

        for (String[] dates : List.of(
                new String[] {"2026-12-05", "2026-12-05"},
                new String[] {"2026-12-05", "2026-12-04"},
                new String[] {"2026-11-01", "2026-11-05"},
                new String[] {"2026-12-01", "2027-01-05"},
                new String[] {"2026-12-01", "2026-12-02"},
                new String[] {"2026-12-05T00:00:00Z", "2026-12-06"},
                new String[] {"abc", "2026-12-06"})) {
            assertThat(quote(id, dates[0], dates[1], userA1()).status()).as(dates[0] + " " + dates[1]).isEqualTo(400);
        }
    }

    @Test
    void quoteHidesOtherPeoplesBookings() throws Exception {
        long id = confirmed(STANDARD_A, "2026-12-01", "2026-12-02", 1, userA1());

        assertThat(quote(id, "2026-12-05", "2026-12-06", IAM.token("USER", TENANT_A, USER_A2)).status()).isEqualTo(404);
        assertThat(quote(id, "2026-12-05", "2026-12-06", IAM.token("USER", TENANT_B, USER_B1)).status()).isEqualTo(404);
        assertThat(quote(id, "2026-12-05", "2026-12-06", IAM.token("ADMIN", TENANT_B, ADMIN_B)).status()).isEqualTo(404);
        assertThat(quote(id, "2026-12-05", "2026-12-06", adminA()).status()).isEqualTo(200);
    }

    @Test
    void unpaidCancelledOrStartedBookingsCannotBeQuoted() throws Exception {
        long pending = send("POST", "/bookings", userA1(), """
                {"roomTypeId":1,"checkIn":"2026-12-10","checkOut":"2026-12-11","roomCount":1}
                """).body().get("id").asLong();
        assertThat(quote(pending, "2026-12-15", "2026-12-16", userA1()).status()).isEqualTo(409);

        send("POST", "/bookings/" + pending + "/cancel", userA1(), null);
        assertThat(quote(pending, "2026-12-15", "2026-12-16", userA1()).status()).isEqualTo(409);
    }

    /** Hôm nay theo đồng hồ test là 2026-11-03: đơn nhận phòng hôm nay không còn đổi được, ngày mai thì còn. */
    @Test
    void bookingThatReachedCheckInDateCannotBeChanged() throws Exception {
        long today = confirmed(STANDARD_A, "2026-11-03", "2026-11-04", 1, userA1());
        long tomorrow = confirmed(STANDARD_A, "2026-11-04", "2026-11-05", 1, userA1());

        assertThat(quote(today, "2026-12-15", "2026-12-16", userA1()).status()).isEqualTo(409);
        assertThat(reschedule(today, 1, "2026-12-15", "2026-12-16", adminA()).status()).isEqualTo(409);
        assertThat(sold(STANDARD_A, "2026-12-15")).isZero();

        assertThat(quote(tomorrow, "2026-12-15", "2026-12-16", userA1()).status()).isEqualTo(200);
    }

    // ---------- Đổi ngày ----------

    @Test
    void rescheduleMovesSoldAndRecomputesTotal() throws Exception {
        long id = confirmed(STANDARD_A, "2026-12-01", "2026-12-03", 2, userA1());

        Reply reply = reschedule(id, 77, "2026-12-10", "2026-12-13", adminA());

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body().get("checkIn").asText()).isEqualTo("2026-12-10");
        assertThat(reply.body().get("checkOut").asText()).isEqualTo("2026-12-13");
        assertThat(reply.body().get("totalAmount").decimalValue()).isEqualByComparingTo("3000000");
        assertThat(reply.body().get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(sold(STANDARD_A, "2026-12-01")).isZero();
        assertThat(sold(STANDARD_A, "2026-12-02")).isZero();
        assertThat(sold(STANDARD_A, "2026-12-10")).isEqualTo(2);
        assertThat(sold(STANDARD_A, "2026-12-12")).isEqualTo(2);
        assertThat(sold(STANDARD_A, "2026-12-13")).isZero();
    }

    @Test
    void overlappingStaysOnlyChangeTheNightsThatDiffer() throws Exception {
        long id = confirmed(RACE_A, "2026-12-01", "2026-12-03", 1, userA1());

        assertThat(reschedule(id, 1, "2026-12-02", "2026-12-04", adminA()).status()).isEqualTo(200);

        assertThat(sold(RACE_A, "2026-12-01")).isZero();
        assertThat(sold(RACE_A, "2026-12-02")).isEqualTo(1);
        assertThat(sold(RACE_A, "2026-12-03")).isEqualTo(1);
    }

    @Test
    void shorterStayLowersTotalAndReleasesNights() throws Exception {
        long id = confirmed(STANDARD_A, "2026-12-01", "2026-12-04", 1, userA1());

        Reply reply = reschedule(id, 1, "2026-12-01", "2026-12-02", adminA());

        assertThat(reply.body().get("totalAmount").decimalValue()).isEqualByComparingTo("500000");
        assertThat(sold(STANDARD_A, "2026-12-01")).isEqualTo(1);
        assertThat(sold(STANDARD_A, "2026-12-02")).isZero();
        assertThat(sold(STANDARD_A, "2026-12-03")).isZero();
    }

    /** Giá mỗi đêm theo đơn gốc, nên sửa giá phòng sau khi đặt không làm đổi cách tính. */
    @Test
    void totalKeepsTheOriginalNightlyPriceAfterPriceChange() throws Exception {
        long id = confirmed(STANDARD_A, "2026-12-01", "2026-12-02", 1, userA1());
        jdbc.update("UPDATE room_type SET base_price = 999000 WHERE id = ?", STANDARD_A);
        try {
            Reply reply = reschedule(id, 1, "2026-12-10", "2026-12-12", adminA());
            assertThat(reply.body().get("totalAmount").decimalValue()).isEqualByComparingTo("1000000");
        } finally {
            jdbc.update("UPDATE room_type SET base_price = 500000 WHERE id = ?", STANDARD_A);
        }
    }

    @Test
    void lastCheckBeforeApplyingRejectsSoldOutNightAndChangesNothing() throws Exception {
        long id = confirmed(RACE_A, "2026-12-01", "2026-12-02", 1, userA1());
        confirmed(RACE_A, "2026-12-11", "2026-12-12", 1, IAM.token("USER", TENANT_A, USER_A2));

        Reply reply = reschedule(id, 1, "2026-12-10", "2026-12-13", adminA());

        assertThat(reply.status()).isEqualTo(409);
        assertThat(sold(RACE_A, "2026-12-01")).isEqualTo(1);
        assertThat(sold(RACE_A, "2026-12-10")).isZero();
        assertThat(sold(RACE_A, "2026-12-12")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_reschedule", Long.class)).isZero();
        assertThat(send("GET", "/bookings/" + id, userA1(), null).body().get("checkIn").asText()).isEqualTo("2026-12-01");
    }

    @Test
    void repeatedRequestIdDoesNotApplyTwice() throws Exception {
        long id = confirmed(STANDARD_A, "2026-12-01", "2026-12-03", 1, userA1());
        assertThat(reschedule(id, 5, "2026-12-10", "2026-12-12", adminA()).status()).isEqualTo(200);

        Reply again = reschedule(id, 5, "2026-12-10", "2026-12-12", adminA());

        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("checkIn").asText()).isEqualTo("2026-12-10");
        assertThat(sold(STANDARD_A, "2026-12-10")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_reschedule", Long.class)).isEqualTo(1);
    }

    @Test
    void sameRequestIdWithDifferentDatesIsRejected() throws Exception {
        long id = confirmed(STANDARD_A, "2026-12-01", "2026-12-03", 1, userA1());
        reschedule(id, 5, "2026-12-10", "2026-12-12", adminA());

        assertThat(reschedule(id, 5, "2026-12-20", "2026-12-22", adminA()).status()).isEqualTo(409);
        assertThat(sold(STANDARD_A, "2026-12-20")).isZero();
    }

    @Test
    void onlyAdminOfTheTenantCanReschedule() throws Exception {
        long id = confirmed(STANDARD_A, "2026-12-01", "2026-12-02", 1, userA1());

        assertThat(reschedule(id, 1, "2026-12-05", "2026-12-06", userA1()).status()).isEqualTo(403);
        assertThat(reschedule(id, 1, "2026-12-05", "2026-12-06", IAM.token("ADMIN", TENANT_B, ADMIN_B)).status())
                .isEqualTo(404);
        assertThat(sold(STANDARD_A, "2026-12-01")).isEqualTo(1);
    }

    @Test
    void invalidBodyIs400EvenForAdmin() throws Exception {
        long id = confirmed(STANDARD_A, "2026-12-01", "2026-12-02", 1, userA1());
        for (String body : List.of("{}", "{bad", """
                {"requestId":"1","checkIn":"2026-12-05","checkOut":"2026-12-06"}
                """, """
                {"requestId":1,"checkIn":"2026-12-05T00:00:00Z","checkOut":"2026-12-06"}
                """, """
                {"requestId":0,"checkIn":"2026-12-05","checkOut":"2026-12-06"}
                """)) {
            assertThat(send("POST", "/bookings/" + id + "/reschedule", adminA(), body).status()).as(body).isEqualTo(400);
        }
    }

    @Test
    void cannotRescheduleBookingThatIsNoLongerConfirmed() throws Exception {
        long pending = send("POST", "/bookings", userA1(), """
                {"roomTypeId":1,"checkIn":"2026-12-10","checkOut":"2026-12-11","roomCount":1}
                """).body().get("id").asLong();

        assertThat(reschedule(pending, 1, "2026-12-15", "2026-12-16", adminA()).status()).isEqualTo(409);
        assertThat(sold(STANDARD_A, "2026-12-15")).isZero();
    }

    /** Hai đơn cùng xin phòng cuối ở cùng đêm: đúng một đơn được đổi, sold không vượt total. */
    @Test
    void concurrentReschedulesForTheLastRoomHaveOneWinner() throws Exception {
        long first = confirmed(RACE_A, "2026-12-01", "2026-12-02", 1, userA1());
        long second = confirmed(RACE_A, "2026-12-03", "2026-12-04", 1, IAM.token("USER", TENANT_A, USER_A2));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> reschedule(first, 1, "2026-12-20", "2026-12-21", adminA()).status()));
            futures.add(pool.submit(() -> reschedule(second, 2, "2026-12-20", "2026-12-21", adminA()).status()));
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> future : futures) {
                codes.add(future.get());
            }
            assertThat(codes).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(sold(RACE_A, "2026-12-20")).isEqualTo(1);
    }

    @Test
    void otherTenantStockIsNeverTouched() throws Exception {
        confirmed(STANDARD_A, "2026-12-01", "2026-12-02", 1, userA1());

        assertThat(sold(STANDARD_B, "2026-12-01")).isZero();
    }
}
