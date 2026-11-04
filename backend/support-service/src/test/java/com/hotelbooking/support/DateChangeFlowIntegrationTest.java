package com.hotelbooking.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelbooking.support.testing.FakeBookingService;
import com.hotelbooking.support.testing.TestJwtIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataAccessException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.hotelbooking.support.testing.TestJwtIssuer.TENANT_A;
import static com.hotelbooking.support.testing.TestJwtIssuer.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Support thật với PostgreSQL thật; Booking được giả lập qua HTTP để kiểm soát mọi phản hồi.
 * Luồng thật qua cả Booking được kiểm tra riêng khi chạy đủ hệ thống.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DateChangeFlowIntegrationTest {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    private static final String USER_A1 = "user-a1@example.test";
    private static final String USER_A2 = "user-a2@example.test";
    private static final String ADMIN_A = "admin-a@example.test";
    private static final String USER_B1 = "user-b1@example.test";
    private static final String ADMIN_B = "admin-b@example.test";
    private static final String PATH = "/support/date-change-requests";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TestJwtIssuer IAM = new TestJwtIssuer();
    private static final FakeBookingService BOOKING = new FakeBookingService();

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
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("security.jwt.issuer", () -> TestJwtIssuer.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", IAM::jwkSetUri);
        registry.add("booking.base-url", BOOKING::baseUrl);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetData() {
        BOOKING.reset();
        jdbc.execute("TRUNCATE notification_outbox, date_change_request, booking_reschedule, booking RESTART IDENTITY");
        jdbc.update("DELETE FROM app_user");
        String insert = "INSERT INTO app_user (email, tenant_id, role) VALUES (?, ?, ?)";
        jdbc.update(insert, USER_A1, TENANT_A, "USER");
        jdbc.update(insert, USER_A2, TENANT_A, "USER");
        jdbc.update(insert, ADMIN_A, TENANT_A, "ADMIN");
        jdbc.update(insert, USER_B1, TENANT_B, "USER");
        jdbc.update(insert, ADMIN_B, TENANT_B, "ADMIN");
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

    private long booking(long tenant, String email) {
        return jdbc.queryForObject("""
                INSERT INTO booking (tenant_id, room_type_id, user_email, check_in, check_out, room_count,
                                     total_amount, status, payment_expires_at)
                VALUES (?, ?, ?, DATE '2026-12-01', DATE '2026-12-03', 2, 2000000, 'CONFIRMED', now())
                RETURNING id
                """, Long.class, tenant, tenant == TENANT_A ? 1 : 3, email);
    }

    private Reply create(String token, long bookingId) throws IOException, InterruptedException {
        return send("POST", PATH, token, """
                {"bookingId":%d,"checkIn":"2026-12-10","checkOut":"2026-12-13","description":"Đổi vì lịch công tác"}
                """.formatted(bookingId));
    }

    private long pending() throws IOException, InterruptedException {
        Reply reply = create(userA1(), booking(TENANT_A, USER_A1));
        assertThat(reply.status()).isEqualTo(201);
        return reply.body().get("id").asLong();
    }

    private Reply approve(String token, long id) throws IOException, InterruptedException {
        return send("POST", PATH + "/" + id + "/approve", token, null);
    }

    private Reply reject(String token, long id, String body) throws IOException, InterruptedException {
        return send("POST", PATH + "/" + id + "/reject", token, body);
    }

    private long outboxCount() {
        return jdbc.queryForObject("SELECT count(*) FROM notification_outbox", Long.class);
    }

    private long userId(String email) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);
    }

    // ---------- Schema ----------

    @Test
    void migrationsRemovedTicketsAndUseOwnHistory() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name IN ('support_ticket', 'ticket_message')",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history_support WHERE success AND version IN ('1', '2')", Long.class))
                .isEqualTo(2);
    }

    @Test
    void databaseAllowsOnlyOnePendingRequestPerBooking() throws Exception {
        long id = pending();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO date_change_request (tenant_id, booking_id, requester_user_id, room_type_name, room_count,
                    current_check_in, current_check_out, requested_check_in, requested_check_out,
                    current_total, new_total, description)
                SELECT tenant_id, booking_id, requester_user_id, room_type_name, room_count, current_check_in,
                    current_check_out, requested_check_in, requested_check_out, current_total, new_total, description
                FROM date_change_request WHERE id = ?
                """, id)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void databaseRejectsRequestForBookingOfAnotherTenant() {
        long bookingOfB = booking(TENANT_B, USER_B1);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO date_change_request (tenant_id, booking_id, requester_user_id, room_type_name, room_count,
                    current_check_in, current_check_out, requested_check_in, requested_check_out,
                    current_total, new_total, description)
                VALUES (?, ?, ?, 'x', 1, DATE '2026-12-01', DATE '2026-12-02', DATE '2026-12-03', DATE '2026-12-04',
                    1, 1, 'd')
                """, TENANT_A, bookingOfB, userId(USER_A1))).isInstanceOf(DataAccessException.class);
    }

    // ---------- Gửi phiếu ----------

    @Test
    void userCreatesRequestWithSnapshotFromBooking() throws Exception {
        long bookingId = booking(TENANT_A, USER_A1);

        Reply reply = create(userA1(), bookingId);

        assertThat(reply.status()).isEqualTo(201);
        JsonNode body = reply.body();
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
        assertThat(body.get("bookingId").asLong()).isEqualTo(bookingId);
        assertThat(body.get("tenantId").asLong()).isEqualTo(TENANT_A);
        assertThat(body.get("requesterEmail").asText()).isEqualTo(USER_A1);
        assertThat(body.get("roomTypeName").asText()).isEqualTo("Standard");
        assertThat(body.get("roomCount").asInt()).isEqualTo(2);
        assertThat(body.get("currentCheckIn").asText()).isEqualTo("2026-12-01");
        assertThat(body.get("requestedCheckIn").asText()).isEqualTo("2026-12-10");
        assertThat(body.get("requestedCheckOut").asText()).isEqualTo("2026-12-13");
        assertThat(body.get("currentTotalAmount").decimalValue()).isEqualByComparingTo("2000000");
        assertThat(body.get("newTotalAmount").decimalValue()).isEqualByComparingTo("3000000");
        assertThat(body.get("description").asText()).isEqualTo("Đổi vì lịch công tác");
        assertThat(body.get("decidedAt").isNull()).isTrue();
    }

    @Test
    void createForwardsTheCallersTokenToBooking() throws Exception {
        String token = userA1();
        create(token, booking(TENANT_A, USER_A1));

        FakeBookingService.Call call = BOOKING.calls().get(0);
        assertThat(call.authorization()).isEqualTo("Bearer " + token);
        assertThat(call.path()).endsWith("/reschedule-quote");
    }

    @Test
    void sendingARequestDoesNotChangeTheBooking() throws Exception {
        pending();
        assertThat(BOOKING.rescheduleCalls()).isEmpty();
        assertThat(outboxCount()).isZero();
    }

    @Test
    void noRoomsMeansTheRequestCannotBeSent() throws Exception {
        BOOKING.noRoomsAvailable();

        assertThat(create(userA1(), booking(TENANT_A, USER_A1)).status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM date_change_request", Long.class)).isZero();
    }

    @Test
    void bookingAnswersAreTranslated() throws Exception {
        long bookingId = booking(TENANT_A, USER_A1);
        for (int[] expected : new int[][] {{400, 400}, {404, 404}, {409, 409}, {403, 403}, {500, 503}, {401, 503}}) {
            BOOKING.quoteReturns(expected[0]);
            assertThat(create(userA1(), bookingId).status()).as("booking " + expected[0]).isEqualTo(expected[1]);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM date_change_request", Long.class)).isZero();
    }

    @Test
    void secondPendingRequestForSameBookingIs409UntilDecided() throws Exception {
        long bookingId = booking(TENANT_A, USER_A1);
        long first = create(userA1(), bookingId).body().get("id").asLong();

        assertThat(create(userA1(), bookingId).status()).isEqualTo(409);

        assertThat(reject(adminA(), first, null).status()).isEqualTo(200);
        assertThat(create(userA1(), bookingId).status()).isEqualTo(201);
    }

    @Test
    void adminCannotSendRequests() throws Exception {
        assertThat(create(adminA(), booking(TENANT_A, USER_A1)).status()).isEqualTo(403);
        assertThat(BOOKING.calls()).isEmpty();
    }

    @Test
    void userNotInAppUserOrWithChangedRoleIs403() throws Exception {
        long bookingId = booking(TENANT_A, USER_A1);
        assertThat(create(IAM.token("USER", TENANT_A, "ghost@example.test"), bookingId).status()).isEqualTo(403);
        assertThat(create(IAM.token("USER", TENANT_B, USER_A1), bookingId).status()).isEqualTo(403);
        jdbc.update("UPDATE app_user SET role = 'USER' WHERE email = ?", ADMIN_A);
        assertThat(send("GET", PATH, adminA(), null).status()).isEqualTo(403);
    }

    @Test
    void invalidBodiesAre400() throws Exception {
        for (String body : List.of(
                "{bad", "{}",
                "{\"bookingId\":1,\"checkIn\":\"2026-12-10\",\"checkOut\":\"2026-12-13\"}",
                "{\"bookingId\":1,\"checkIn\":\"2026-12-10\",\"checkOut\":\"2026-12-13\",\"description\":\"  \"}",
                "{\"bookingId\":1,\"checkIn\":\"2026-12-10\",\"checkOut\":\"2026-12-13\",\"description\":5}",
                "{\"bookingId\":1,\"checkIn\":\"2026-12-10\",\"checkOut\":\"2026-12-13\",\"description\":\"<b>x</b>\"}",
                "{\"bookingId\":\"1\",\"checkIn\":\"2026-12-10\",\"checkOut\":\"2026-12-13\",\"description\":\"d\"}",
                "{\"bookingId\":0,\"checkIn\":\"2026-12-10\",\"checkOut\":\"2026-12-13\",\"description\":\"d\"}",
                "{\"bookingId\":1,\"checkIn\":\"2026-12-10T00:00:00Z\",\"checkOut\":\"2026-12-13\",\"description\":\"d\"}")) {
            assertThat(send("POST", PATH, userA1(), body).status()).as(body).isEqualTo(400);
        }
        assertThat(BOOKING.calls()).isEmpty();
    }

    @Test
    void unknownFieldsCannotChooseTotalStatusOrTenant() throws Exception {
        long bookingId = booking(TENANT_A, USER_A1);
        Reply reply = send("POST", PATH, userA1(), """
                {"bookingId":%d,"checkIn":"2026-12-10","checkOut":"2026-12-13","description":"d",
                 "newTotalAmount":1,"status":"APPROVED","tenantId":2}
                """.formatted(bookingId));
        assertThat(reply.body().get("newTotalAmount").decimalValue()).isEqualByComparingTo("3000000");
        assertThat(reply.body().get("status").asText()).isEqualTo("PENDING");
        assertThat(reply.body().get("tenantId").asLong()).isEqualTo(TENANT_A);
    }

    // ---------- Xem ----------

    @Test
    void userSeesOwnRequestsAdminSeesTenantNewestFirst() throws Exception {
        long mine = pending();
        long sameTenant = create(IAM.token("USER", TENANT_A, USER_A2), booking(TENANT_A, USER_A2)).body().get("id").asLong();
        long otherTenant = create(IAM.token("USER", TENANT_B, USER_B1), booking(TENANT_B, USER_B1)).body().get("id").asLong();

        assertThat(ids(userA1(), "")).containsExactly(mine);
        assertThat(ids(adminA(), "")).containsExactly(sameTenant, mine);
        assertThat(ids(IAM.token("ADMIN", TENANT_B, ADMIN_B), "")).containsExactly(otherTenant);
    }

    private List<Long> ids(String token, String query) throws IOException, InterruptedException {
        Reply reply = send("GET", PATH + query, token, null);
        assertThat(reply.status()).isEqualTo(200);
        List<Long> ids = new ArrayList<>();
        reply.body().get("items").forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }

    @Test
    void listFiltersByStatusAndPaginates() throws Exception {
        long first = pending();
        long second = create(userA1(), booking(TENANT_A, USER_A1)).body().get("id").asLong();
        reject(adminA(), first, null);

        assertThat(ids(adminA(), "?status=PENDING")).containsExactly(second);
        assertThat(ids(adminA(), "?status=REJECTED")).containsExactly(first);
        assertThat(ids(adminA(), "?status=APPROVED")).isEmpty();

        Reply page = send("GET", PATH + "?page=0&size=1", adminA(), null);
        assertThat(page.body().get("items")).hasSize(1);
        assertThat(page.body().get("totalElements").asLong()).isEqualTo(2);
        assertThat(page.body().get("totalPages").asInt()).isEqualTo(2);

        for (String query : List.of("?status=DONE", "?page=-1", "?size=0", "?size=101")) {
            assertThat(send("GET", PATH + query, adminA(), null).status()).as(query).isEqualTo(400);
        }
    }

    @Test
    void foreignRequestsLookLikeMissingOnes() throws Exception {
        long id = pending();
        Reply missing = send("GET", PATH + "/99999", userA1(), null);
        for (String token : List.of(IAM.token("USER", TENANT_A, USER_A2), IAM.token("USER", TENANT_B, USER_B1),
                IAM.token("ADMIN", TENANT_B, ADMIN_B))) {
            Reply foreign = send("GET", PATH + "/" + id, token, null);
            assertThat(foreign.status()).isEqualTo(404);
            assertThat(foreign.body().get("detail")).isEqualTo(missing.body().get("detail"));
        }
        assertThat(send("GET", PATH + "/" + id, adminA(), null).status()).isEqualTo(200);
        assertThat(send("GET", PATH + "/" + id, userA1(), null).status()).isEqualTo(200);
    }

    // ---------- Duyệt ----------

    @Test
    void approveChangesBookingThenQueuesNotificationForTheRequester() throws Exception {
        long id = pending();
        String token = adminA();

        Reply reply = approve(token, id);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body().get("status").asText()).isEqualTo("APPROVED");
        assertThat(reply.body().get("decidedAt").asText()).endsWith("Z");

        FakeBookingService.Call call = BOOKING.rescheduleCalls().get(0);
        assertThat(call.authorization()).isEqualTo("Bearer " + token);
        JsonNode sent = JSON.readTree(call.body());
        assertThat(sent.get("requestId").asLong()).isEqualTo(id);
        assertThat(sent.get("checkIn").asText()).isEqualTo("2026-12-10");
        assertThat(sent.get("checkOut").asText()).isEqualTo("2026-12-13");

        var event = jdbc.queryForMap("SELECT * FROM notification_outbox");
        assertThat(event.get("type")).isEqualTo("DATE_CHANGE_APPROVED");
        assertThat(event.get("recipient_user_id")).isEqualTo(userId(USER_A1));
        assertThat(event.get("reference_id")).isEqualTo(id);
        assertThat((String) event.get("body")).contains("2026-12-10", "2026-12-13", "Standard", "3.000.000");
        assertThat(event.get("processed_at")).isNull();
    }

    @Test
    void whenRoomsRanOutApprovalFailsAndRequestStaysPending() throws Exception {
        long id = pending();
        BOOKING.rescheduleReturns(409);

        assertThat(approve(adminA(), id).status()).isEqualTo(409);

        assertThat(send("GET", PATH + "/" + id, adminA(), null).body().get("status").asText()).isEqualTo("PENDING");
        assertThat(outboxCount()).isZero();
        assertThat(reject(adminA(), id, "{\"note\":\"Đã hết phòng\"}").status()).isEqualTo(200);
    }

    @Test
    void bookingDownMeansNothingIsRecorded() throws Exception {
        long id = pending();
        BOOKING.rescheduleReturns(500);

        assertThat(approve(adminA(), id).status()).isEqualTo(503);

        assertThat(send("GET", PATH + "/" + id, adminA(), null).body().get("status").asText()).isEqualTo("PENDING");
        assertThat(outboxCount()).isZero();
    }

    @Test
    void decidedRequestsCannotBeDecidedAgain() throws Exception {
        long approved = pending();
        approve(adminA(), approved);
        assertThat(approve(adminA(), approved).status()).isEqualTo(409);
        assertThat(reject(adminA(), approved, null).status()).isEqualTo(409);

        long rejected = create(userA1(), booking(TENANT_A, USER_A1)).body().get("id").asLong();
        reject(adminA(), rejected, null);
        assertThat(approve(adminA(), rejected).status()).isEqualTo(409);
        assertThat(reject(adminA(), rejected, null).status()).isEqualTo(409);

        assertThat(BOOKING.rescheduleCalls()).hasSize(1);
        assertThat(outboxCount()).isEqualTo(2);
    }

    @Test
    void onlyAdminOfTheTenantCanDecide() throws Exception {
        long id = pending();
        assertThat(approve(userA1(), id).status()).isEqualTo(403);
        assertThat(reject(userA1(), id, null).status()).isEqualTo(403);
        assertThat(reject(userA1(), id, "{bad").status()).as("role is checked before the body").isEqualTo(403);
        String otherAdmin = IAM.token("ADMIN", TENANT_B, ADMIN_B);
        assertThat(approve(otherAdmin, id).status()).isEqualTo(404);
        assertThat(reject(otherAdmin, id, null).status()).isEqualTo(404);
        assertThat(BOOKING.rescheduleCalls()).isEmpty();
        assertThat(outboxCount()).isZero();
    }

    // ---------- Từ chối ----------

    @Test
    void rejectLeavesBookingAloneAndSendsTheReason() throws Exception {
        long id = pending();

        Reply reply = reject(adminA(), id, "{\"note\":\"  Ngày đó khách sạn bảo trì \"}");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body().get("status").asText()).isEqualTo("REJECTED");
        assertThat(reply.body().get("decisionNote").asText()).isEqualTo("Ngày đó khách sạn bảo trì");
        assertThat(BOOKING.rescheduleCalls()).isEmpty();
        var event = jdbc.queryForMap("SELECT * FROM notification_outbox");
        assertThat(event.get("type")).isEqualTo("DATE_CHANGE_REJECTED");
        assertThat(event.get("recipient_user_id")).isEqualTo(userId(USER_A1));
        assertThat((String) event.get("body")).contains("Ngày đó khách sạn bảo trì", "2026-12-01", "2026-12-03");
    }

    @Test
    void reasonIsOptionalButMustBePlainText() throws Exception {
        long withoutBody = pending();
        assertThat(reject(adminA(), withoutBody, null).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT body FROM notification_outbox", String.class)).doesNotContain("Lý do");

        long bad = pending();
        assertThat(reject(adminA(), bad, "{\"note\":\"<script>\"}").status()).isEqualTo(400);
        assertThat(reject(adminA(), bad, "{\"note\":\"" + "a".repeat(1001) + "\"}").status()).isEqualTo(400);
        assertThat(reject(adminA(), bad, "{\"note\":5}").status()).isEqualTo(400);
        assertThat(reject(adminA(), bad, "{\"note\":\"   \"}").status()).isEqualTo(200);
    }

    /** Booking đã đổi ngày nhưng phiếu chưa kịp ghi nhận: không được từ chối, và duyệt lại phải hoàn tất. */
    @Test
    void afterAPartialApprovalRejectIsBlockedAndApproveFinishes() throws Exception {
        long id = pending();
        long bookingId = jdbc.queryForObject("SELECT booking_id FROM date_change_request WHERE id = ?", Long.class, id);
        jdbc.update("""
                INSERT INTO booking_reschedule (tenant_id, request_id, booking_id, previous_check_in, previous_check_out,
                    new_check_in, new_check_out, previous_total, new_total)
                VALUES (?, ?, ?, DATE '2026-12-01', DATE '2026-12-03', DATE '2026-12-10', DATE '2026-12-13', 1, 1)
                """, TENANT_A, id, bookingId);

        assertThat(reject(adminA(), id, null).status()).isEqualTo(409);
        assertThat(outboxCount()).isZero();

        assertThat(approve(adminA(), id).status()).isEqualTo(200);
        assertThat(outboxCount()).isEqualTo(1);
    }

    // ---------- Đồng thời ----------

    @Test
    void concurrentDecisionsHaveExactlyOneWinnerAndOneNotification() throws Exception {
        long id = pending();
        List<Callable<Integer>> actions = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            actions.add(() -> approve(adminA(), id).status());
            actions.add(() -> reject(adminA(), id, null).status());
        }
        ExecutorService pool = Executors.newFixedThreadPool(actions.size());
        try {
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> future : pool.invokeAll(actions)) {
                codes.add(future.get());
            }
            assertThat(codes).containsOnly(200, 409);
            assertThat(codes.stream().filter(code -> code == 200)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(outboxCount()).isEqualTo(1);
        assertThat(BOOKING.rescheduleCalls().size()).isLessThanOrEqualTo(1);
    }

    @Test
    void concurrentCreatesForSameBookingLeaveOnePending() throws Exception {
        long bookingId = booking(TENANT_A, USER_A1);
        List<Callable<Integer>> actions = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            actions.add(() -> create(userA1(), bookingId).status());
        }
        ExecutorService pool = Executors.newFixedThreadPool(actions.size());
        try {
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> future : pool.invokeAll(actions)) {
                codes.add(future.get());
            }
            assertThat(codes).containsOnly(201, 409);
            assertThat(codes.stream().filter(code -> code == 201)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
