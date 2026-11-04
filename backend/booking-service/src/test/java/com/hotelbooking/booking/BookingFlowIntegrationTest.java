package com.hotelbooking.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelbooking.booking.service.BookingExpiryJob;
import com.hotelbooking.booking.support.TestJwtIssuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
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
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
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
 * Chạy service thật với PostgreSQL thật: Flyway, ddl-auto=validate, JWT qua JWKS, trừ và trả room_inventory.
 * Các bảng của IAM và Catalog được tạo từ đúng migration của hai service đó, theo thứ tự IAM -> Catalog -> Booking.
 * Sau mỗi test đều chạy truy vấn đối soát ở docs/erd-v2.md §8 (T37 trong docs/api-contract.md §7.2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class BookingFlowIntegrationTest {

    static {
        // Kết nối chạy migration của A và B mở trước khi BookingServiceApplication đặt UTC (xem class đó).
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    /** ID loại phòng theo thứ tự seed trong catalog-service V2__seed_rooms.sql. */
    private static final long STANDARD_A = 1; // 3 phòng, 500.000
    private static final long DELUXE_A = 2; // 4 phòng, 900.000
    private static final long STANDARD_B = 3; // tenant 2, 5 phòng
    private static final long RACE_A = 5; // 1 phòng

    private static final String USER_A1 = "user-a1@example.test";
    private static final String USER_A2 = "user-a2@example.test";
    private static final String ADMIN_A = "admin-a@example.test";
    private static final String USER_B1 = "user-b1@example.test";
    private static final String ADMIN_B = "admin-b@example.test";

    /** Ngày cố định để test không hỏng khi thời gian trôi qua khỏi khoảng mở bán của seed. */
    private static final Instant NOW = Instant.parse("2026-11-03T02:15:30Z");

    private static final String RECONCILIATION_SQL = """
            SELECT count(*) FROM (
                SELECT i.tenant_id
                FROM room_inventory i
                LEFT JOIN booking b
                  ON b.tenant_id = i.tenant_id
                 AND b.room_type_id = i.room_type_id
                 AND b.status IN ('PENDING_PAYMENT', 'CONFIRMED')
                 AND i.stay_date >= b.check_in
                 AND i.stay_date <  b.check_out
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
        applyUpstreamMigrations();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("security.jwt.issuer", () -> TestJwtIssuer.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", IAM::jwkSetUri);
        // Test tự gọi job để biết chắc thời điểm chạy.
        registry.add("booking.expiry.interval", () -> "PT1H");
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

    @Autowired
    private BookingExpiryJob expiryJob;

    @Autowired
    private ScheduledAnnotationBeanPostProcessor scheduler;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM booking");
        jdbc.update("UPDATE room_inventory SET sold = 0");
        jdbc.update("UPDATE room_type SET base_price = 500000 WHERE id = ?", STANDARD_A);
        jdbc.update("DELETE FROM app_user");
        String insert = "INSERT INTO app_user (email, tenant_id, role) VALUES (?, ?, ?)";
        jdbc.update(insert, USER_A1, TENANT_A, "USER");
        jdbc.update(insert, USER_A2, TENANT_A, "USER");
        jdbc.update(insert, ADMIN_A, TENANT_A, "ADMIN");
        jdbc.update(insert, USER_B1, TENANT_B, "USER");
        jdbc.update(insert, ADMIN_B, TENANT_B, "ADMIN");
    }

    /** T37: sold phải luôn khớp với các booking đang giữ phòng. */
    @AfterEach
    void soldMatchesBookings() {
        assertThat(jdbc.queryForObject(RECONCILIATION_SQL, Long.class)).isZero();
    }

    // ---------- Schema ----------

    @Test
    void flywayUsesOwnHistoryTableOnSharedDatabase() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history_booking WHERE version = '1' AND success", Integer.class);

        assertThat(applied).isEqualTo(1);
    }

    @Test
    void schemaHasNoLegacyRoomArtifacts() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'booking' AND column_name = 'room_id'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE conrelid = 'booking'::regclass AND contype = 'x'
                """, Integer.class)).isZero();
    }

    // ---------- Tạo booking ----------

    /** T09: 2 đêm, 1 phòng. */
    @Test
    void create_holdsEachNightButNotCheckOutNight() throws Exception {
        HttpResponse<String> response = book(STANDARD_A, "2026-11-24", "2026-11-26", 1, userA1());

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("id").asLong()).isPositive();
        assertThat(body.get("tenantId").asLong()).isEqualTo(TENANT_A);
        assertThat(body.get("roomTypeId").asLong()).isEqualTo(STANDARD_A);
        assertThat(body.get("userEmail").asText()).isEqualTo(USER_A1);
        assertThat(body.get("checkIn").asText()).isEqualTo("2026-11-24");
        assertThat(body.get("checkOut").asText()).isEqualTo("2026-11-26");
        assertThat(body.get("roomCount").asInt()).isEqualTo(1);
        assertThat(body.get("totalAmount").decimalValue()).isEqualByComparingTo("1000000");
        assertThat(body.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(body.get("createdAt").asText()).isEqualTo("2026-11-03T02:15:30Z");
        assertThat(body.get("paymentExpiresAt").asText()).isEqualTo("2026-11-03T02:30:30Z");

        assertThat(sold(STANDARD_A, "2026-11-23")).isZero();
        assertThat(sold(STANDARD_A, "2026-11-24")).isEqualTo(1);
        assertThat(sold(STANDARD_A, "2026-11-25")).isEqualTo(1);
        assertThat(sold(STANDARD_A, "2026-11-26")).isZero();
    }

    @Test
    void create_totalMultipliesPriceNightsAndRooms() throws Exception {
        HttpResponse<String> response = book(DELUXE_A, "2026-12-01", "2026-12-04", 2, userA1());

        assertThat(JSON.readTree(response.body()).get("totalAmount").decimalValue())
                .isEqualByComparingTo("5400000");
        assertThat(sold(DELUXE_A, "2026-12-03")).isEqualTo(2);
    }

    /** T10: không có idempotency key, đây là giới hạn đã công bố. */
    @Test
    void sameRequestTwice_createsSecondBooking() throws Exception {
        assertThat(book(STANDARD_A, "2026-11-24", "2026-11-26", 1, userA1()).statusCode()).isEqualTo(201);
        assertThat(book(STANDARD_A, "2026-11-24", "2026-11-26", 1, userA1()).statusCode()).isEqualTo(201);

        assertThat(bookingCount()).isEqualTo(2);
        assertThat(sold(STANDARD_A, "2026-11-24")).isEqualTo(2);
    }

    /** Thiếu ở đêm thứ hai thì đêm đầu đã trừ tạm cũng phải được rollback. */
    @Test
    void oneNightSoldOut_returns409AndRollsBackOtherNights() throws Exception {
        assertThat(book(RACE_A, "2026-12-11", "2026-12-12", 1, userA1()).statusCode()).isEqualTo(201);

        assertThat(book(RACE_A, "2026-12-10", "2026-12-13", 1, userA2()).statusCode()).isEqualTo(409);

        assertThat(sold(RACE_A, "2026-12-10")).isZero();
        assertThat(sold(RACE_A, "2026-12-11")).isEqualTo(1);
        assertThat(sold(RACE_A, "2026-12-12")).isZero();
        assertThat(bookingCount()).isEqualTo(1);
    }

    @Test
    void roomCountAboveRemainingStock_returns409() throws Exception {
        assertThat(book(STANDARD_A, "2026-12-10", "2026-12-11", 4, userA1()).statusCode()).isEqualTo(409);
        assertThat(sold(STANDARD_A, "2026-12-10")).isZero();

        assertThat(book(STANDARD_A, "2026-12-10", "2026-12-11", 3, userA1()).statusCode()).isEqualTo(201);
        assertThat(sold(STANDARD_A, "2026-12-10")).isEqualTo(3);
    }

    /** T28: đêm 01/02/2027 chưa có dòng tồn nghĩa là chưa mở bán. */
    @Test
    void nightNotOpenForSale_returns409WithoutTouchingOpenNight() throws Exception {
        assertThat(book(STANDARD_A, "2027-01-31", "2027-02-02", 1, userA1()).statusCode()).isEqualTo(409);

        assertThat(sold(STANDARD_A, "2027-01-31")).isZero();
        assertThat(bookingCount()).isZero();
    }

    /** T22: trả phòng ngày 29 và nhận phòng ngày 29 không dùng chung đêm nào. */
    @Test
    void adjacentStaysShareNoNight() throws Exception {
        assertThat(book(DELUXE_A, "2026-12-27", "2026-12-29", 4, userA1()).statusCode()).isEqualTo(201);
        assertThat(book(DELUXE_A, "2026-12-29", "2026-12-31", 4, userA2()).statusCode()).isEqualTo(201);
        assertThat(book(DELUXE_A, "2026-12-28", "2026-12-30", 1, userA2()).statusCode()).isEqualTo(409);

        for (String night : List.of("2026-12-27", "2026-12-28", "2026-12-29", "2026-12-30")) {
            assertThat(sold(DELUXE_A, night)).isEqualTo(4);
        }
    }

    /** T23: đồng hồ test đứng ở 09:15 ngày 03/11 giờ Việt Nam. */
    @Test
    void checkInInPast_returns400_butTodayIsAllowed() throws Exception {
        assertThat(book(STANDARD_A, "2026-11-02", "2026-11-04", 1, userA1()).statusCode()).isEqualTo(400);
        assertThat(bookingCount()).isZero();

        assertThat(book(STANDARD_A, "2026-11-03", "2026-11-04", 1, userA1()).statusCode()).isEqualTo(201);
    }

    /** T12. */
    @Test
    void roomTypeOfAnotherTenantOrMissing_returnsSame404WithoutChanges() throws Exception {
        HttpResponse<String> otherTenant = book(STANDARD_B, "2026-12-10", "2026-12-11", 1, userA1());
        HttpResponse<String> missing = book(999_999, "2026-12-10", "2026-12-11", 1, userA1());

        assertThat(otherTenant.statusCode()).isEqualTo(404);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(detail(otherTenant)).isEqualTo(detail(missing));
        assertThat(sold(STANDARD_B, "2026-12-10")).isZero();
        assertThat(bookingCount()).isZero();
    }

    /** T25: nếu trả 409 sẽ tiết lộ lịch đặt phòng của tenant khác. */
    @Test
    void soldOutRoomTypeOfAnotherTenant_stillReturns404() throws Exception {
        assertThat(book(STANDARD_B, "2026-12-10", "2026-12-11", 5, userB1()).statusCode()).isEqualTo(201);

        HttpResponse<String> crossTenant = book(STANDARD_B, "2026-12-10", "2026-12-11", 1, userA1());
        HttpResponse<String> missing = book(999_999, "2026-12-10", "2026-12-11", 1, userA1());

        assertThat(crossTenant.statusCode()).isEqualTo(404);
        assertThat(detail(crossTenant)).isEqualTo(detail(missing));
        assertThat(sold(STANDARD_B, "2026-12-10")).isEqualTo(5);
    }

    /** Chỉ biết user chưa provision khi INSERT, lúc đó tồn đã bị trừ nên phải được rollback. */
    @Test
    void userMissingFromTenant_returns403AndRollsBackInventory() throws Exception {
        String notProvisioned = IAM.token("USER", TENANT_A, "ghost@example.test");
        String wrongTenant = IAM.token("USER", TENANT_A, USER_B1);

        assertThat(book(STANDARD_A, "2026-12-10", "2026-12-12", 1, notProvisioned).statusCode()).isEqualTo(403);
        assertThat(book(STANDARD_A, "2026-12-10", "2026-12-12", 1, wrongTenant).statusCode()).isEqualTo(403);

        assertThat(sold(STANDARD_A, "2026-12-10")).isZero();
        assertThat(sold(STANDARD_A, "2026-12-11")).isZero();
        assertThat(bookingCount()).isZero();
    }

    /** Đọc room_type theo tenant đứng trước INSERT, nên tenant không tồn tại nhận 404 như mọi loại phòng lạ. */
    @Test
    void tenantMissingFromDatabase_returns404WithoutRow() throws Exception {
        String unknownTenant = IAM.token("USER", 999L, USER_A1);

        assertThat(book(STANDARD_A, "2026-12-10", "2026-12-11", 1, unknownTenant).statusCode()).isEqualTo(404);
        assertThat(bookingCount()).isZero();
    }

    @Test
    void uppercaseEmailInJwt_matchesLowercaseAppUser() throws Exception {
        String token = IAM.token("USER", TENANT_A, "User-A1@Example.TEST");

        HttpResponse<String> response = book(STANDARD_A, "2026-12-10", "2026-12-11", 1, token);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(JSON.readTree(response.body()).get("userEmail").asText()).isEqualTo(USER_A1);
        assertThat(ids(get("/bookings", userA1()))).hasSize(1);
    }

    /** T36. */
    @Test
    void priceChange_onlyAffectsNewBookings() throws Exception {
        long oldBooking = createdId(book(STANDARD_A, "2026-12-10", "2026-12-11", 1, userA1()));

        jdbc.update("UPDATE room_type SET base_price = 700000 WHERE id = ?", STANDARD_A);
        HttpResponse<String> newBooking = book(STANDARD_A, "2026-12-10", "2026-12-11", 1, userA1());

        assertThat(JSON.readTree(newBooking.body()).get("totalAmount").decimalValue()).isEqualByComparingTo("700000");
        assertThat(JSON.readTree(get("/bookings/" + oldBooking, userA1()).body()).get("totalAmount").decimalValue())
                .isEqualByComparingTo("500000");
    }

    // ---------- Thanh toán ----------

    /** T29, T30. */
    @Test
    void payment_confirmsOnceAndKeepsInventoryHeld() throws Exception {
        long id = createdId(book(STANDARD_A, "2026-12-10", "2026-12-12", 2, userA1()));

        HttpResponse<String> paid = post("/bookings/" + id + "/payment", userA1());

        assertThat(paid.statusCode()).isEqualTo(200);
        assertThat(status(paid)).isEqualTo("CONFIRMED");
        assertThat(sold(STANDARD_A, "2026-12-10")).isEqualTo(2);
        assertThat(sold(STANDARD_A, "2026-12-11")).isEqualTo(2);

        assertThat(post("/bookings/" + id + "/payment", userA1()).statusCode()).isEqualTo(409);
        assertThat(storedStatus(id)).isEqualTo("CONFIRMED");
    }

    /** T30: đã quá hạn nhưng job chưa chạy. Đúng thời điểm hết hạn cũng là quá hạn. */
    @Test
    void paymentAtOrAfterDeadline_returns409AndDoesNotConfirm() throws Exception {
        long id = createdId(book(STANDARD_A, "2026-12-10", "2026-12-12", 1, userA1()));
        setPaymentDeadline(id, NOW);

        assertThat(post("/bookings/" + id + "/payment", userA1()).statusCode()).isEqualTo(409);

        assertThat(storedStatus(id)).isEqualTo("PENDING_PAYMENT");
        assertThat(sold(STANDARD_A, "2026-12-10")).isEqualTo(1);
    }

    @Test
    void paymentJustBeforeDeadline_succeeds() throws Exception {
        long id = createdId(book(STANDARD_A, "2026-12-10", "2026-12-12", 1, userA1()));
        setPaymentDeadline(id, NOW.plusSeconds(1));

        assertThat(post("/bookings/" + id + "/payment", userA1()).statusCode()).isEqualTo(200);
    }

    /** T31. */
    @Test
    void paymentByOtherUser_returns404_andByAdmin_returns403() throws Exception {
        long id = createdId(book(STANDARD_A, "2026-12-10", "2026-12-12", 1, userA1()));

        assertThat(post("/bookings/" + id + "/payment", userA2()).statusCode()).isEqualTo(404);
        assertThat(post("/bookings/" + id + "/payment", userB1()).statusCode()).isEqualTo(404);
        assertThat(post("/bookings/" + id + "/payment", adminA()).statusCode()).isEqualTo(403);
        assertThat(post("/bookings/999999/payment", userA1()).statusCode()).isEqualTo(404);

        assertThat(storedStatus(id)).isEqualTo("PENDING_PAYMENT");
    }

    // ---------- Huỷ ----------

    /** T32: huỷ lại vẫn 200, nhưng tồn chỉ được trả một lần. Đơn thứ hai để phát hiện nếu trả hai lần. */
    @Test
    void cancel_releasesInventoryExactlyOnce() throws Exception {
        long id = createdId(book(STANDARD_A, "2026-12-10", "2026-12-12", 1, userA1()));
        assertThat(book(STANDARD_A, "2026-12-10", "2026-12-12", 1, userA2()).statusCode()).isEqualTo(201);
        assertThat(sold(STANDARD_A, "2026-12-10")).isEqualTo(2);

        HttpResponse<String> first = post("/bookings/" + id + "/cancel", userA1());
        HttpResponse<String> second = post("/bookings/" + id + "/cancel", userA1());

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(status(first)).isEqualTo("CANCELLED");
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(status(second)).isEqualTo("CANCELLED");
        assertThat(sold(STANDARD_A, "2026-12-10")).isEqualTo(1);
        assertThat(sold(STANDARD_A, "2026-12-11")).isEqualTo(1);
    }

    @Test
    void cancelledRooms_canBeBookedAgain_andCancelledBookingCannotBePaid() throws Exception {
        long id = createdId(book(RACE_A, "2026-12-10", "2026-12-12", 1, userA1()));
        assertThat(book(RACE_A, "2026-12-10", "2026-12-12", 1, userA2()).statusCode()).isEqualTo(409);

        assertThat(post("/bookings/" + id + "/cancel", userA1()).statusCode()).isEqualTo(200);

        assertThat(post("/bookings/" + id + "/payment", userA1()).statusCode()).isEqualTo(409);
        assertThat(book(RACE_A, "2026-12-10", "2026-12-12", 1, userA2()).statusCode()).isEqualTo(201);
    }

    @Test
    void cancelConfirmedBooking_returns409AndKeepsInventory() throws Exception {
        long id = createdId(book(STANDARD_A, "2026-12-10", "2026-12-12", 1, userA1()));
        assertThat(post("/bookings/" + id + "/payment", userA1()).statusCode()).isEqualTo(200);

        assertThat(post("/bookings/" + id + "/cancel", userA1()).statusCode()).isEqualTo(409);

        assertThat(storedStatus(id)).isEqualTo("CONFIRMED");
        assertThat(sold(STANDARD_A, "2026-12-10")).isEqualTo(1);
    }

    /** Hết hạn nhưng job chưa chạy thì đơn vẫn PENDING_PAYMENT, nên vẫn được huỷ và chỉ trả tồn một lần. */
    @Test
    void cancelOverdueBookingBeforeJobRuns_isCancelled() throws Exception {
        long id = createdId(book(STANDARD_A, "2026-12-10", "2026-12-12", 1, userA1()));
        setPaymentDeadline(id, NOW.minusSeconds(60));

        assertThat(post("/bookings/" + id + "/cancel", userA1()).statusCode()).isEqualTo(200);
        expiryJob.expireOverdue();

        assertThat(storedStatus(id)).isEqualTo("CANCELLED");
        assertThat(sold(STANDARD_A, "2026-12-10")).isZero();
    }

    /** §5.6 và ERD §10: chỉ USER là chủ đơn được huỷ. */
    @Test
    void cancelByOtherUser_returns404_andByAdmin_returns403() throws Exception {
        long id = createdId(book(STANDARD_A, "2026-12-10", "2026-12-12", 1, userA1()));

        assertThat(post("/bookings/" + id + "/cancel", userA2()).statusCode()).isEqualTo(404);
        assertThat(post("/bookings/" + id + "/cancel", userB1()).statusCode()).isEqualTo(404);
        assertThat(post("/bookings/" + id + "/cancel", adminA()).statusCode()).isEqualTo(403);
        assertThat(post("/bookings/999999/cancel", userA1()).statusCode()).isEqualTo(404);

        assertThat(storedStatus(id)).isEqualTo("PENDING_PAYMENT");
        assertThat(sold(STANDARD_A, "2026-12-10")).isEqualTo(1);
    }

    // ---------- Hết hạn ----------

    /** T34. */
    @Test
    void expiryJob_expiresOverdueBookingAndReleasesInventoryOnce() throws Exception {
        long overdue = createdId(book(STANDARD_A, "2026-12-10", "2026-12-12", 2, userA1()));
        long stillValid = createdId(book(STANDARD_A, "2026-12-10", "2026-12-12", 1, userA2()));
        setPaymentDeadline(overdue, NOW);

        expiryJob.expireOverdue();
        expiryJob.expireOverdue();

        assertThat(storedStatus(overdue)).isEqualTo("EXPIRED");
        assertThat(storedStatus(stillValid)).isEqualTo("PENDING_PAYMENT");
        assertThat(sold(STANDARD_A, "2026-12-10")).isEqualTo(1);
        assertThat(sold(STANDARD_A, "2026-12-11")).isEqualTo(1);

        assertThat(post("/bookings/" + overdue + "/cancel", userA1()).statusCode()).isEqualTo(409);
        assertThat(post("/bookings/" + overdue + "/payment", userA1()).statusCode()).isEqualTo(409);
        assertThat(sold(STANDARD_A, "2026-12-10")).isEqualTo(1);
    }

    @Test
    void expiryJob_leavesConfirmedAndCancelledBookingsAlone() throws Exception {
        long confirmed = createdId(book(STANDARD_A, "2026-12-10", "2026-12-12", 1, userA1()));
        long cancelled = createdId(book(STANDARD_A, "2026-12-10", "2026-12-12", 1, userA1()));
        assertThat(post("/bookings/" + confirmed + "/payment", userA1()).statusCode()).isEqualTo(200);
        assertThat(post("/bookings/" + cancelled + "/cancel", userA1()).statusCode()).isEqualTo(200);
        setPaymentDeadline(confirmed, NOW.minusSeconds(60));
        setPaymentDeadline(cancelled, NOW.minusSeconds(60));

        expiryJob.expireOverdue();

        assertThat(storedStatus(confirmed)).isEqualTo("CONFIRMED");
        assertThat(storedStatus(cancelled)).isEqualTo("CANCELLED");
        assertThat(sold(STANDARD_A, "2026-12-10")).isEqualTo(1);
    }

    @Test
    void expiryJob_coversEveryTenant() throws Exception {
        long tenantA = createdId(book(STANDARD_A, "2026-12-10", "2026-12-11", 1, userA1()));
        long tenantB = createdId(book(STANDARD_B, "2026-12-10", "2026-12-11", 1, userB1()));
        setPaymentDeadline(tenantA, NOW);
        setPaymentDeadline(tenantB, NOW);

        expiryJob.expireOverdue();

        assertThat(storedStatus(tenantA)).isEqualTo("EXPIRED");
        assertThat(storedStatus(tenantB)).isEqualTo("EXPIRED");
    }

    @Test
    void expiryJobIsScheduled() {
        assertThat(scheduler.getScheduledTasks())
                .extracting(Object::toString)
                .anyMatch(task -> task.contains("BookingExpiryJob.expireOverdue"));
    }

    // ---------- Request đồng thời ----------

    /** T11: loại phòng 5 chỉ có 1 phòng. */
    @RepeatedTest(5)
    void concurrentRequestsForSameStay_exactlyOneWins() throws Exception {
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String token = i % 2 == 0 ? userA1() : userA2();
            calls.add(() -> book(RACE_A, "2026-12-20", "2026-12-23", 1, token).statusCode());
        }

        List<Integer> statuses = runConcurrently(calls);

        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
        assertThat(bookingCount()).isEqualTo(1);
        assertThat(sold(RACE_A, "2026-12-20")).isEqualTo(1);
        assertThat(sold(RACE_A, "2026-12-21")).isEqualTo(1);
        assertThat(sold(RACE_A, "2026-12-22")).isEqualTo(1);
    }

    /** Các kỳ ở đan xen nhau: khóa theo ngày tăng dần nên không được có deadlock (sẽ thành 500). */
    @RepeatedTest(5)
    void concurrentOverlappingStays_neverDeadlockOrOversell() throws Exception {
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String checkIn = "2026-12-" + (10 + i % 4);
            String checkOut = "2026-12-" + (14 + i % 3);
            String token = i % 2 == 0 ? userA1() : userA2();
            calls.add(() -> book(STANDARD_A, checkIn, checkOut, 1, token).statusCode());
        }

        List<Integer> statuses = runConcurrently(calls);

        assertThat(statuses).containsOnly(201, 409);
        assertThat(jdbc.queryForObject("""
                SELECT max(sold) FROM room_inventory
                WHERE room_type_id = ? AND stay_date BETWEEN '2026-12-10' AND '2026-12-16'
                """, Integer.class, STANDARD_A)).isEqualTo(3);
    }

    @RepeatedTest(5)
    void concurrentCancels_releaseInventoryExactlyOnce() throws Exception {
        long id = createdId(book(STANDARD_A, "2026-12-10", "2026-12-12", 1, userA1()));
        assertThat(book(STANDARD_A, "2026-12-10", "2026-12-12", 1, userA2()).statusCode()).isEqualTo(201);

        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            calls.add(() -> post("/bookings/" + id + "/cancel", userA1()).statusCode());
        }

        assertThat(runConcurrently(calls)).containsOnly(200);
        assertThat(sold(STANDARD_A, "2026-12-10")).isEqualTo(1);
        assertThat(sold(STANDARD_A, "2026-12-11")).isEqualTo(1);
    }

    @RepeatedTest(5)
    void concurrentPaymentAndCancel_onlyOneChangesStatus() throws Exception {
        long id = createdId(book(STANDARD_A, "2026-12-10", "2026-12-12", 1, userA1()));

        List<Integer> statuses = runConcurrently(List.of(
                () -> post("/bookings/" + id + "/payment", userA1()).statusCode(),
                () -> post("/bookings/" + id + "/cancel", userA1()).statusCode()));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        int expectedSold = "CONFIRMED".equals(storedStatus(id)) ? 1 : 0;
        assertThat(storedStatus(id)).isIn("CONFIRMED", "CANCELLED");
        assertThat(sold(STANDARD_A, "2026-12-10")).isEqualTo(expectedSold);
    }

    @RepeatedTest(5)
    void concurrentCancelAndExpiry_releaseInventoryExactlyOnce() throws Exception {
        long id = createdId(book(STANDARD_A, "2026-12-10", "2026-12-12", 1, userA1()));
        assertThat(book(STANDARD_A, "2026-12-10", "2026-12-12", 1, userA2()).statusCode()).isEqualTo(201);
        setPaymentDeadline(id, NOW);

        runConcurrently(List.of(
                () -> post("/bookings/" + id + "/cancel", userA1()).statusCode(),
                () -> {
                    expiryJob.expireOverdue();
                    return 0;
                }));

        assertThat(storedStatus(id)).isIn("CANCELLED", "EXPIRED");
        assertThat(sold(STANDARD_A, "2026-12-10")).isEqualTo(1);
    }

    // ---------- Quyền đọc ----------

    /** T14, T15, T16. */
    @Test
    void readsAreIsolatedByOwnerRoleAndTenant() throws Exception {
        long a1Booking = createdId(book(STANDARD_A, "2026-12-10", "2026-12-11", 1, userA1()));
        long a2Booking = createdId(book(DELUXE_A, "2026-12-10", "2026-12-11", 1, userA2()));

        assertThat(ids(get("/bookings", userA1()))).containsExactly(a1Booking);
        assertThat(ids(get("/bookings", adminA()))).containsExactly(a1Booking, a2Booking);
        assertThat(ids(get("/bookings", userB1()))).isEmpty();
        assertThat(ids(get("/bookings", adminB()))).isEmpty();

        assertThat(get("/bookings/" + a1Booking, userA1()).statusCode()).isEqualTo(200);
        assertThat(get("/bookings/" + a2Booking, adminA()).statusCode()).isEqualTo(200);

        HttpResponse<String> otherOwner = get("/bookings/" + a1Booking, userA2());
        HttpResponse<String> otherTenant = get("/bookings/" + a1Booking, adminB());
        HttpResponse<String> missing = get("/bookings/999999", userA1());
        assertThat(Stream.of(otherOwner, otherTenant, missing).map(HttpResponse::statusCode))
                .containsOnly(404);
        assertThat(detail(otherOwner)).isEqualTo(detail(missing));
        assertThat(detail(otherTenant)).isEqualTo(detail(missing));
    }

    // ---------- Helpers ----------

    private static List<Integer> runConcurrently(List<Callable<Integer>> calls) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(calls.size())) {
            List<Future<Integer>> results = calls.stream()
                    .map(call -> pool.submit(() -> {
                        start.await();
                        return call.call();
                    }))
                    .toList();
            start.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            return statuses;
        }
    }

    private long bookingCount() {
        return jdbc.queryForObject("SELECT count(*) FROM booking", Long.class);
    }

    private int sold(long roomTypeId, String stayDate) {
        return jdbc.queryForObject(
                "SELECT sold FROM room_inventory WHERE room_type_id = ? AND stay_date = CAST(? AS DATE)",
                Integer.class, roomTypeId, stayDate);
    }

    private String storedStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM booking WHERE id = ?", String.class, id);
    }

    private void setPaymentDeadline(long id, Instant deadline) {
        jdbc.update("UPDATE booking SET payment_expires_at = CAST(? AS TIMESTAMPTZ) WHERE id = ?",
                deadline.toString(), id);
    }

    private HttpResponse<String> book(long roomTypeId, String checkIn, String checkOut, int roomCount, String token)
            throws Exception {
        String body = JSON.createObjectNode()
                .put("roomTypeId", roomTypeId)
                .put("checkIn", checkIn)
                .put("checkOut", checkOut)
                .put("roomCount", roomCount)
                .toString();
        HttpRequest request = HttpRequest.newBuilder(uri("/bookings"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody())
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

    private static String status(HttpResponse<String> response) throws IOException {
        return JSON.readTree(response.body()).get("status").asText();
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

    private static String adminA() {
        return IAM.token("ADMIN", TENANT_A, ADMIN_A);
    }

    private static String userB1() {
        return IAM.token("USER", TENANT_B, USER_B1);
    }

    private static String adminB() {
        return IAM.token("ADMIN", TENANT_B, ADMIN_B);
    }
}
