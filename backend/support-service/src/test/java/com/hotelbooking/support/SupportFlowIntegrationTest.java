package com.hotelbooking.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.hotelbooking.support.testing.TestJwtIssuer.TENANT_A;
import static com.hotelbooking.support.testing.TestJwtIssuer.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Chạy service thật với PostgreSQL thật: Flyway, ddl-auto=validate, JWT qua JWKS, khóa dòng và FK.
 * Các bảng phụ thuộc được tạo từ đúng migration của IAM, Catalog và Booking.
 * Không dùng disabledWithoutDocker: thiếu Docker thì test phải fail, không được âm thầm bỏ qua.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SupportFlowIntegrationTest {

    static {
        // Kết nối chạy migration của service khác mở trước khi SupportServiceApplication đặt UTC (xem class đó).
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

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

    /** Gradle chạy test từ thư mục support-service. */
    private static void applyUpstreamMigrations() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            for (String script : List.of(
                    "../iam-service/src/main/resources/db/migration/V1__create_tenant_and_app_user.sql",
                    "../iam-service/src/main/resources/db/migration/V2__seed_tenants.sql",
                    "../catalog-service/src/main/resources/db/migration/V1__create_room.sql",
                    "../catalog-service/src/main/resources/db/migration/V2__seed_rooms.sql",
                    "../booking-service/src/main/resources/db/migration/V1__create_booking.sql")) {
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
        jdbc.execute("TRUNCATE ticket_message, support_ticket, booking RESTART IDENTITY");
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

    private static String user(String email, long tenant) {
        return IAM.token("USER", tenant, email);
    }

    private static String admin(String email, long tenant) {
        return IAM.token("ADMIN", tenant, email);
    }

    private static String userA1() {
        return user(USER_A1, TENANT_A);
    }

    private static String adminA() {
        return admin(ADMIN_A, TENANT_A);
    }

    private Reply send(String method, String path, String token, String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json");
        request.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(),
                response.body().isEmpty() ? JSON.nullNode() : JSON.readTree(response.body()));
    }

    private Reply createTicket(String token, Long bookingId) throws IOException, InterruptedException {
        String booking = bookingId == null ? "" : "\"bookingId\":" + bookingId + ",";
        return send("POST", "/support/tickets", token,
                "{" + booking + "\"subject\":\"Cần hỗ trợ\",\"description\":\"Đổi giờ đến\"}");
    }

    private long openTicket(String token) throws IOException, InterruptedException {
        Reply reply = createTicket(token, null);
        assertThat(reply.status()).isEqualTo(201);
        return reply.body().get("id").asLong();
    }

    private Reply changeStatus(String token, long id, String status) throws IOException, InterruptedException {
        return send("PATCH", "/support/tickets/" + id + "/status", token, "{\"status\":\"" + status + "\"}");
    }

    private Reply addMessage(String token, long id, String body) throws IOException, InterruptedException {
        return send("POST", "/support/tickets/" + id + "/messages", token, "{\"body\":\"" + body + "\"}");
    }

    private long insertBooking(long tenantId, String email) {
        long roomType = tenantId == TENANT_A ? 1 : 3;
        return jdbc.queryForObject("""
                INSERT INTO booking (tenant_id, room_type_id, user_email, check_in, check_out, room_count,
                                     total_amount, payment_expires_at)
                VALUES (?, ?, ?, DATE '2026-12-01', DATE '2026-12-02', 1, 500000, now() + interval '15 minutes')
                RETURNING id
                """, Long.class, tenantId, roomType, email);
    }

    private long userId(String email) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);
    }

    // ---------- Schema ----------

    @Test
    void flywayUsesItsOwnHistoryTable() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history_support WHERE success AND version = '1'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void databaseRejectsTicketPointingAcrossTenants() {
        long bookingOfB = insertBooking(TENANT_B, USER_B1);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO support_ticket (tenant_id, requester_user_id, booking_id, subject, description)
                VALUES (?, ?, ?, 's', 'd')
                """, TENANT_A, userId(USER_A1), bookingOfB)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO support_ticket (tenant_id, requester_user_id, subject, description)
                VALUES (?, ?, 's', 'd')
                """, TENANT_A, userId(USER_B1))).isInstanceOf(DataAccessException.class);
    }

    @Test
    void databaseRejectsMessageFromAnotherTenant() throws Exception {
        long id = openTicket(userA1());
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ticket_message (tenant_id, ticket_id, author_user_id, author_role, body)
                VALUES (?, ?, ?, 'USER', 'x')
                """, TENANT_B, id, userId(USER_B1))).isInstanceOf(DataAccessException.class);
    }

    @Test
    void messagesAreImmutableInTheDatabase() throws Exception {
        long id = openTicket(userA1());
        assertThat(addMessage(userA1(), id, "Lời nhắn gốc").status()).isEqualTo(201);
        assertThatThrownBy(() -> jdbc.update("UPDATE ticket_message SET body = 'sửa'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ticket_message"))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT body FROM ticket_message", String.class)).isEqualTo("Lời nhắn gốc");
    }

    @Test
    void databaseRequiresClosedAtExactlyWhenClosed() throws Exception {
        long id = openTicket(userA1());
        assertThatThrownBy(() -> jdbc.update("UPDATE support_ticket SET status = 'CLOSED' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE support_ticket SET closed_at = now() WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    /** Lệnh reset trong README phải vẫn chạy được khi đã có ticket trỏ tới booking. */
    @Test
    void documentedBookingResetStillWorksWithCascade() throws Exception {
        long booking = insertBooking(TENANT_A, USER_A1);
        assertThat(createTicket(userA1(), booking).status()).isEqualTo(201);
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE booking RESTART IDENTITY"))
                .isInstanceOf(DataAccessException.class);
        jdbc.execute("TRUNCATE booking RESTART IDENTITY CASCADE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM support_ticket", Long.class)).isZero();
    }

    // ---------- Tạo ticket ----------

    @Test
    void userCreatesTicketForOwnBooking() throws Exception {
        long booking = insertBooking(TENANT_A, USER_A1);
        Reply reply = createTicket(userA1(), booking);
        assertThat(reply.status()).isEqualTo(201);
        assertThat(reply.body().get("status").asText()).isEqualTo("OPEN");
        assertThat(reply.body().get("bookingId").asLong()).isEqualTo(booking);
        assertThat(reply.body().get("tenantId").asLong()).isEqualTo(TENANT_A);
        assertThat(reply.body().get("requesterEmail").asText()).isEqualTo(USER_A1);
        assertThat(reply.body().get("createdAt").asText()).endsWith("Z");
        assertThat(reply.body().get("closedAt").isNull()).isTrue();
    }

    @Test
    void userCreatesGeneralTicketWithoutBooking() throws Exception {
        Reply reply = createTicket(userA1(), null);
        assertThat(reply.status()).isEqualTo(201);
        assertThat(reply.body().get("bookingId").isNull()).isTrue();
    }

    @Test
    void bookingOfSomeoneElseIsSameAsNotFound() throws Exception {
        Reply sameTenant = createTicket(userA1(), insertBooking(TENANT_A, USER_A2));
        Reply otherTenant = createTicket(userA1(), insertBooking(TENANT_B, USER_B1));
        Reply missing = createTicket(userA1(), 99999L);
        assertThat(List.of(sameTenant.status(), otherTenant.status(), missing.status())).containsOnly(404);
        assertThat(sameTenant.body().get("detail")).isEqualTo(missing.body().get("detail"));
        assertThat(otherTenant.body().get("detail")).isEqualTo(missing.body().get("detail"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM support_ticket", Long.class)).isZero();
    }

    @Test
    void adminCannotCreateTicket() throws Exception {
        assertThat(createTicket(adminA(), null).status()).isEqualTo(403);
    }

    @Test
    void bodyCannotChooseTenantRequesterOrStatus() throws Exception {
        Reply reply = send("POST", "/support/tickets", userA1(), """
                {"subject":"s","description":"d","tenantId":2,"status":"CLOSED",
                 "requesterUserId":%d,"requesterEmail":"%s"}
                """.formatted(userId(USER_B1), USER_B1));
        assertThat(reply.status()).isEqualTo(201);
        assertThat(reply.body().get("tenantId").asLong()).isEqualTo(TENANT_A);
        assertThat(reply.body().get("status").asText()).isEqualTo("OPEN");
        assertThat(reply.body().get("requesterEmail").asText()).isEqualTo(USER_A1);
    }

    @Test
    void userNotInAppUserIs403() throws Exception {
        String ghost = user("ghost@example.test", TENANT_A);
        assertThat(createTicket(ghost, null).status()).isEqualTo(403);
        assertThat(send("GET", "/support/tickets", ghost, null).status()).isEqualTo(403);
    }

    @Test
    void tokenWhoseTenantOrRoleNoLongerMatchesDatabaseIs403() throws Exception {
        assertThat(createTicket(user(USER_A1, TENANT_B), null).status()).isEqualTo(403);
        jdbc.update("UPDATE app_user SET role = 'USER' WHERE email = ?", ADMIN_A);
        assertThat(send("GET", "/support/tickets", adminA(), null).status()).isEqualTo(403);
    }

    @Test
    void emailInTokenIsNormalized() throws Exception {
        Reply reply = createTicket(user("USER-A1@Example.Test", TENANT_A), null);
        assertThat(reply.status()).isEqualTo(201);
        assertThat(reply.body().get("requesterEmail").asText()).isEqualTo(USER_A1);
    }

    // ---------- Xem ----------

    @Test
    void userListsOnlyOwnTicketsAdminListsTenantOnly() throws Exception {
        long mine = openTicket(userA1());
        long sameTenant = openTicket(user(USER_A2, TENANT_A));
        long otherTenant = openTicket(user(USER_B1, TENANT_B));

        assertThat(listIds(userA1())).containsExactly(mine);
        assertThat(listIds(adminA())).containsExactly(mine, sameTenant);
        assertThat(listIds(admin(ADMIN_B, TENANT_B))).containsExactly(otherTenant);

        JsonNode first = send("GET", "/support/tickets", adminA(), null).body().get("items").get(0);
        assertThat(first.get("requesterEmail").asText()).isEqualTo(USER_A1);
    }

    private List<Long> listIds(String token) throws IOException, InterruptedException {
        Reply reply = send("GET", "/support/tickets", token, null);
        assertThat(reply.status()).isEqualTo(200);
        List<Long> ids = new ArrayList<>();
        reply.body().get("items").forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }

    @Test
    void listPaginates() throws Exception {
        long first = openTicket(userA1());
        long second = openTicket(userA1());
        long third = openTicket(userA1());

        Reply page0 = send("GET", "/support/tickets?page=0&size=2", userA1(), null);
        assertThat(page0.body().get("items").get(0).get("id").asLong()).isEqualTo(first);
        assertThat(page0.body().get("items").get(1).get("id").asLong()).isEqualTo(second);
        assertThat(page0.body().get("totalElements").asLong()).isEqualTo(3);
        assertThat(page0.body().get("totalPages").asInt()).isEqualTo(2);

        Reply page1 = send("GET", "/support/tickets?page=1&size=2", userA1(), null);
        assertThat(page1.body().get("items")).hasSize(1);
        assertThat(page1.body().get("items").get(0).get("id").asLong()).isEqualTo(third);

        Reply beyond = send("GET", "/support/tickets?page=9&size=2", userA1(), null);
        assertThat(beyond.status()).isEqualTo(200);
        assertThat(beyond.body().get("items")).isEmpty();
    }

    @Test
    void emptyListIsNotAnError() throws Exception {
        Reply reply = send("GET", "/support/tickets", userA1(), null);
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body().get("items")).isEmpty();
        assertThat(reply.body().get("totalElements").asLong()).isZero();
    }

    @Test
    void foreignTicketsLookExactlyLikeMissingOnes() throws Exception {
        long id = openTicket(userA1());
        Reply missing = send("GET", "/support/tickets/99999", userA1(), null);
        for (String token : List.of(user(USER_A2, TENANT_A), user(USER_B1, TENANT_B), admin(ADMIN_B, TENANT_B))) {
            Reply foreign = send("GET", "/support/tickets/" + id, token, null);
            assertThat(foreign.status()).isEqualTo(404);
            assertThat(foreign.body().get("detail")).isEqualTo(missing.body().get("detail"));
            assertThat(addMessage(token, id, "x").status()).isEqualTo(404);
        }
        assertThat(changeStatus(admin(ADMIN_B, TENANT_B), id, "IN_PROGRESS").status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT status FROM support_ticket WHERE id = ?", String.class, id))
                .isEqualTo("OPEN");
    }

    @Test
    void adminSeesTicketOfAnyUserInTenant() throws Exception {
        long id = openTicket(userA1());
        Reply reply = send("GET", "/support/tickets/" + id, adminA(), null);
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body().get("requesterEmail").asText()).isEqualTo(USER_A1);
    }

    // ---------- Tin nhắn ----------

    @Test
    void conversationKeepsOrderAndRecordsAuthorsFromToken() throws Exception {
        long id = openTicket(userA1());
        Reply fromUser = addMessage(userA1(), id, "Cho tôi hỏi");
        Reply fromAdmin = send("POST", "/support/tickets/" + id + "/messages", adminA(),
                "{\"body\":\"Chào bạn\",\"authorUserId\":" + userId(USER_A1) + ",\"authorRole\":\"USER\"}");
        assertThat(fromUser.status()).isEqualTo(201);
        assertThat(fromAdmin.status()).isEqualTo(201);
        assertThat(fromAdmin.body().get("authorUserId").asLong()).isEqualTo(userId(ADMIN_A));
        assertThat(fromAdmin.body().get("authorRole").asText()).isEqualTo("ADMIN");

        JsonNode messages = send("GET", "/support/tickets/" + id, userA1(), null).body().get("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("body").asText()).isEqualTo("Cho tôi hỏi");
        assertThat(messages.get(0).get("authorRole").asText()).isEqualTo("USER");
        assertThat(messages.get(1).get("body").asText()).isEqualTo("Chào bạn");
    }

    @Test
    void authorRoleStaysAsWrittenWhenUserRoleChanges() throws Exception {
        long id = openTicket(userA1());
        addMessage(adminA(), id, "Từ ADMIN");
        jdbc.update("UPDATE app_user SET role = 'USER' WHERE email = ?", ADMIN_A);
        JsonNode messages = send("GET", "/support/tickets/" + id, userA1(), null).body().get("messages");
        assertThat(messages.get(0).get("authorRole").asText()).isEqualTo("ADMIN");
    }

    @Test
    void messageBumpsUpdatedAtAndAllowedUntilClosed() throws Exception {
        long id = openTicket(userA1());
        changeStatus(adminA(), id, "IN_PROGRESS");
        assertThat(addMessage(userA1(), id, "Khi đang xử lý").status()).isEqualTo(201);
        changeStatus(adminA(), id, "RESOLVED");
        assertThat(addMessage(userA1(), id, "Khi đã giải quyết").status()).isEqualTo(201);
        changeStatus(adminA(), id, "CLOSED");
        assertThat(addMessage(userA1(), id, "Sau khi đóng").status()).isEqualTo(409);
        assertThat(addMessage(adminA(), id, "Sau khi đóng").status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_message", Long.class)).isEqualTo(2);
    }

    // ---------- Trạng thái ----------

    @Test
    void fullLifecycleSetsClosedAtOnlyAtTheEnd() throws Exception {
        long id = openTicket(userA1());
        for (String status : List.of("IN_PROGRESS", "RESOLVED")) {
            Reply reply = changeStatus(adminA(), id, status);
            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.body().get("status").asText()).isEqualTo(status);
            assertThat(reply.body().get("closedAt").isNull()).isTrue();
            assertThat(reply.body().get("requesterEmail").asText()).isEqualTo(USER_A1);
        }
        Reply closed = changeStatus(adminA(), id, "CLOSED");
        assertThat(closed.status()).isEqualTo(200);
        assertThat(closed.body().get("closedAt").asText()).endsWith("Z");
        assertThat(send("GET", "/support/tickets/" + id, userA1(), null).body().get("status").asText())
                .isEqualTo("CLOSED");
    }

    @Test
    void transitionsCannotSkipRepeatOrGoBack() throws Exception {
        long id = openTicket(userA1());
        assertThat(changeStatus(adminA(), id, "RESOLVED").status()).isEqualTo(409);
        assertThat(changeStatus(adminA(), id, "CLOSED").status()).isEqualTo(409);
        assertThat(changeStatus(adminA(), id, "OPEN").status()).isEqualTo(409);
        changeStatus(adminA(), id, "IN_PROGRESS");
        assertThat(changeStatus(adminA(), id, "IN_PROGRESS").status()).isEqualTo(409);
        assertThat(changeStatus(adminA(), id, "OPEN").status()).isEqualTo(409);
        changeStatus(adminA(), id, "RESOLVED");
        assertThat(changeStatus(adminA(), id, "IN_PROGRESS").status()).as("reopen waits for G2").isEqualTo(409);
        changeStatus(adminA(), id, "CLOSED");
        for (String status : List.of("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED")) {
            assertThat(changeStatus(adminA(), id, status).status()).isEqualTo(409);
        }
    }

    @Test
    void userCannotChangeStatusOfOwnTicket() throws Exception {
        long id = openTicket(userA1());
        assertThat(changeStatus(userA1(), id, "CLOSED").status()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT status FROM support_ticket WHERE id = ?", String.class, id))
                .isEqualTo("OPEN");
    }

    // ---------- Đồng thời ----------

    @Test
    void concurrentStatusChangesHaveExactlyOneWinner() throws Exception {
        long id = openTicket(userA1());
        List<Integer> codes = runConcurrently(8, () -> changeStatus(adminA(), id, "IN_PROGRESS").status());
        assertThat(codes).containsOnly(200, 409);
        assertThat(codes.stream().filter(code -> code == 200)).hasSize(1);
    }

    /**
     * Giữ transaction đóng ticket chưa commit rồi gửi tin nhắn: request phải chờ khóa dòng và thấy CLOSED.
     * Nếu thiếu khóa, request đọc được trạng thái cũ và lưu tin nhắn vào ticket vừa đóng.
     */
    @Test
    void messageWaitsForConcurrentCloseAndThenIsRejected() throws Exception {
        long id = openTicket(userA1());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection closing = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = closing.createStatement()) {
            closing.setAutoCommit(false);
            statement.executeUpdate(
                    "UPDATE support_ticket SET status = 'CLOSED', closed_at = now() WHERE id = " + id);

            Future<Integer> message = pool.submit(() -> addMessage(userA1(), id, "Gửi lúc đang đóng").status());
            assertThatThrownBy(() -> message.get(2, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

            closing.commit();
            assertThat(message.get(10, TimeUnit.SECONDS)).isEqualTo(409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_message", Long.class)).isZero();
    }

    private static List<Integer> runConcurrently(int count, Callable<Integer> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(action));
            }
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
