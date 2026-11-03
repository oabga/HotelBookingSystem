package com.hotelbooking.booking.api;

import com.hotelbooking.booking.domain.Booking;
import com.hotelbooking.booking.security.CurrentUser;
import com.hotelbooking.booking.security.SecurityConfig;
import com.hotelbooking.booking.service.BookingNotFoundException;
import com.hotelbooking.booking.service.BookingService;
import com.hotelbooking.booking.service.RoomNotFoundException;
import com.hotelbooking.booking.service.RoomUnavailableException;
import com.hotelbooking.booking.service.UserNotProvisionedException;
import com.hotelbooking.booking.support.TestJwtIssuer;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

import static com.hotelbooking.booking.support.TestJwtIssuer.TENANT_A;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Kiểm tra lớp HTTP và bảo mật. Token là JWT thật, không dùng .with(jwt()). */
@WebMvcTest(BookingController.class)
@Import(SecurityConfig.class)
class BookingApiTest {

    private static final TestJwtIssuer IAM = new TestJwtIssuer();
    private static final String USER_EMAIL = "user-a1@example.test";
    private static final String ADMIN_EMAIL = "admin-a@example.test";
    private static final CurrentUser USER_A1 = new CurrentUser(TENANT_A, USER_EMAIL, false);
    private static final CurrentUser ADMIN_A = new CurrentUser(TENANT_A, ADMIN_EMAIL, true);

    private static final Instant CHECK_IN = Instant.parse("2026-12-01T07:00:00Z");
    private static final Instant CHECK_OUT = Instant.parse("2026-12-02T04:00:00Z");
    private static final String VALID_BODY = """
            {"roomId":1,"checkIn":"2026-12-01T14:00:00+07:00","checkOut":"2026-12-02T11:00:00+07:00"}
            """;

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("security.jwt.issuer", () -> TestJwtIssuer.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", IAM::jwkSetUri);
    }

    @Autowired
    private MockMvc mvc;

    @MockBean
    private BookingService service;

    // ---------- POST /bookings: thành công ----------

    @Test
    void userCreatesBooking_returns201InUtc() throws Exception {
        when(service.create(USER_A1, 1L, CHECK_IN, CHECK_OUT)).thenReturn(savedBooking(5L, 1L, USER_EMAIL));

        mvc.perform(createRequest(VALID_BODY, userToken()))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.tenantId").value(TENANT_A))
                .andExpect(jsonPath("$.roomId").value(1))
                .andExpect(jsonPath("$.userEmail").value(USER_EMAIL))
                .andExpect(jsonPath("$.checkIn").value("2026-12-01T07:00:00Z"))
                .andExpect(jsonPath("$.checkOut").value("2026-12-02T04:00:00Z"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-03T02:15:30Z"))
                .andExpect(jsonPath("$.userSub").doesNotExist());
    }

    @Test
    void identityInBodyIsIgnored_tenantAndOwnerComeOnlyFromJwt() throws Exception {
        when(service.create(USER_A1, 1L, CHECK_IN, CHECK_OUT)).thenReturn(savedBooking(5L, 1L, USER_EMAIL));
        String body = """
                {"roomId":1,"checkIn":"2026-12-01T07:00:00Z","checkOut":"2026-12-02T04:00:00Z",
                 "tenantId":2,"userEmail":"attacker@example.test","status":"CANCELLED"}
                """;

        mvc.perform(createRequest(body, userToken()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(TENANT_A))
                .andExpect(jsonPath("$.userEmail").value(USER_EMAIL));

        verify(service).create(USER_A1, 1L, CHECK_IN, CHECK_OUT);
    }

    @Test
    void uppercaseEmailInJwt_isNormalizedBeforeReachingService() throws Exception {
        when(service.create(USER_A1, 1L, CHECK_IN, CHECK_OUT)).thenReturn(savedBooking(5L, 1L, USER_EMAIL));

        mvc.perform(createRequest(VALID_BODY, IAM.token("USER", TENANT_A, "User-A1@Example.TEST")))
                .andExpect(status().isCreated());

        verify(service).create(USER_A1, 1L, CHECK_IN, CHECK_OUT);
    }

    // ---------- POST /bookings: thứ tự lỗi 401 -> 403 -> 400 ----------

    @Test
    void missingToken_returns401() throws Exception {
        mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void adminCannotCreateBooking_returns403() throws Exception {
        mvc.perform(createRequest(VALID_BODY, adminToken()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void adminWithInvalidBody_stillReturns403BecauseRoleIsCheckedFirst() throws Exception {
        mvc.perform(createRequest("{\"roomId\":0}", adminToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidTokenWithInvalidBody_returns401BecauseJwtIsCheckedFirst() throws Exception {
        mvc.perform(createRequest("{\"roomId\":0}", "not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"roomId\":",
            "",
            "null",
            "{\"roomId\":null}",
            "{\"roomId\":0}",
            "{\"roomId\":-1}",
            "{\"roomId\":\"1\"}",
            "{\"roomId\":1.5}",
            "{\"roomId\":1.0}",
            "{\"roomId\":true}",
            "{\"roomId\":[1]}",
            "{\"roomId\":99999999999999999999}"
    })
    void invalidRoomId_returns400(String roomPart) throws Exception {
        String body = roomPart.startsWith("{\"roomId\":") && roomPart.endsWith("}")
                ? roomPart.replace("}", ",\"checkIn\":\"2026-12-01T07:00:00Z\",\"checkOut\":\"2026-12-02T04:00:00Z\"}")
                : roomPart;

        assertBadRequest(body);
    }

    static Stream<String> invalidTimes() {
        return Stream.of(
                // Thiếu hoặc null
                "\"checkOut\":\"2026-12-02T04:00:00Z\"",
                "\"checkIn\":\"2026-12-01T07:00:00Z\"",
                "\"checkIn\":null,\"checkOut\":\"2026-12-02T04:00:00Z\"",
                "\"checkIn\":\"2026-12-01T07:00:00Z\",\"checkOut\":null",
                // Sai định dạng hoặc thiếu múi giờ
                "\"checkIn\":\"2026-12-01T07:00:00\",\"checkOut\":\"2026-12-02T04:00:00Z\"",
                "\"checkIn\":\"2026-12-01T07:00:00Z\",\"checkOut\":\"2026-12-02\"",
                "\"checkIn\":\"tomorrow\",\"checkOut\":\"2026-12-02T04:00:00Z\"",
                "\"checkIn\":\"\",\"checkOut\":\"2026-12-02T04:00:00Z\"",
                "\"checkIn\":\"2026-02-30T07:00:00Z\",\"checkOut\":\"2026-12-02T04:00:00Z\"",
                // Sai kiểu JSON
                "\"checkIn\":1764572400,\"checkOut\":\"2026-12-02T04:00:00Z\"",
                "\"checkIn\":\"2026-12-01T07:00:00Z\",\"checkOut\":[2026,12,2]",
                "\"checkIn\":true,\"checkOut\":\"2026-12-02T04:00:00Z\"",
                // Năm ngoài khoảng database xử lý an toàn
                "\"checkIn\":\"1999-12-31T23:59:59Z\",\"checkOut\":\"2026-12-02T04:00:00Z\"",
                "\"checkIn\":\"2026-12-01T07:00:00Z\",\"checkOut\":\"+10000-01-01T00:00:00Z\"",
                // checkOut không sau checkIn
                "\"checkIn\":\"2026-12-02T04:00:00Z\",\"checkOut\":\"2026-12-02T04:00:00Z\"",
                "\"checkIn\":\"2026-12-02T05:00:00Z\",\"checkOut\":\"2026-12-02T04:00:00Z\"",
                // Cùng một thời điểm dù viết bằng hai múi giờ khác nhau
                "\"checkIn\":\"2026-12-02T11:00:00+07:00\",\"checkOut\":\"2026-12-02T04:00:00Z\"",
                // Bằng nhau sau khi làm tròn về micro giây
                "\"checkIn\":\"2026-12-02T04:00:00.0000001Z\",\"checkOut\":\"2026-12-02T04:00:00.0000009Z\"");
    }

    @ParameterizedTest
    @MethodSource("invalidTimes")
    void invalidStayTime_returns400(String timeFields) throws Exception {
        assertBadRequest("{\"roomId\":1," + timeFields + "}");
    }

    // ---------- POST /bookings: ánh xạ lỗi nghiệp vụ ----------

    @Test
    void roomNotFound_returns404Problem() throws Exception {
        when(service.create(any(), anyLong(), any(), any())).thenThrow(new RoomNotFoundException());

        mvc.perform(createRequest(VALID_BODY, userToken()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Room not found"))
                .andExpect(jsonPath("$.instance").value("/bookings"));
    }

    @Test
    void overlappingTime_returns409Problem() throws Exception {
        when(service.create(any(), anyLong(), any(), any())).thenThrow(new RoomUnavailableException());

        mvc.perform(createRequest(VALID_BODY, userToken()))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Room is not available for the requested time"));
    }

    @Test
    void userNotProvisioned_returns403Problem() throws Exception {
        when(service.create(any(), anyLong(), any(), any())).thenThrow(new UserNotProvisionedException());

        mvc.perform(createRequest(VALID_BODY, userToken()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unexpectedFailure_returns500WithoutInternalDetails() throws Exception {
        when(service.create(any(), anyLong(), any(), any()))
                .thenThrow(new RuntimeException("SQL: insert into booking"));

        mvc.perform(createRequest(VALID_BODY, userToken()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Internal server error"));
    }

    // ---------- GET /bookings ----------

    @Test
    void userListsBookings_usesIdentityFromJwt() throws Exception {
        when(service.list(USER_A1)).thenReturn(List.of(
                savedBooking(1L, 1L, USER_EMAIL),
                savedBooking(2L, 2L, USER_EMAIL)));

        mvc.perform(get("/bookings").header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[1].id").value(2));
    }

    @Test
    void adminListsBookings_usesAdminIdentity() throws Exception {
        when(service.list(ADMIN_A)).thenReturn(List.of(savedBooking(1L, 1L, USER_EMAIL)));

        mvc.perform(get("/bookings").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userEmail").value(USER_EMAIL));
    }

    @Test
    void emptyList_returnsEmptyArray() throws Exception {
        when(service.list(USER_A1)).thenReturn(List.of());

        mvc.perform(get("/bookings").header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    // ---------- GET /bookings/{id} ----------

    @Test
    void getBooking_returns200() throws Exception {
        when(service.get(USER_A1, 7L)).thenReturn(savedBooking(7L, 1L, USER_EMAIL));

        mvc.perform(get("/bookings/7").header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));
    }

    @Test
    void bookingNotVisible_returns404Problem() throws Exception {
        when(service.get(USER_A1, 7L)).thenThrow(new BookingNotFoundException());

        mvc.perform(get("/bookings/7").header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Booking not found"))
                .andExpect(jsonPath("$.instance").value("/bookings/7"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "1.5", "99999999999999999999"})
    void invalidBookingId_returns400Problem(String id) throws Exception {
        mvc.perform(get("/bookings/" + id).header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(service);
    }

    @Test
    void getBookingWithoutToken_returns401() throws Exception {
        mvc.perform(get("/bookings/7")).andExpect(status().isUnauthorized());
    }

    // ---------- Xác minh JWT: mọi token lỗi đều là 401 ----------

    @Test
    void tokenSignedWithUntrustedKey_returns401() throws Exception {
        assertRejected(IAM.signWithUntrustedKey(claims().build()));
    }

    @Test
    void tokenWithAlgNone_returns401() throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(claims().build().toString().getBytes(StandardCharsets.UTF_8));

        assertRejected(header + "." + payload + ".");
    }

    @Test
    void wrongIssuer_returns401() throws Exception {
        assertRejected(IAM.sign(claims().issuer("http://evil.example").build()));
    }

    @Test
    void missingIssuer_returns401() throws Exception {
        assertRejected(IAM.sign(claims().issuer(null).build()));
    }

    @Test
    void expiredToken_returns401() throws Exception {
        Instant twoMinutesAgo = Instant.now().minus(2, ChronoUnit.MINUTES);

        assertRejected(IAM.sign(claims().expirationTime(Date.from(twoMinutesAgo)).build()));
    }

    @Test
    void missingExpiry_returns401() throws Exception {
        assertRejected(IAM.sign(claims().expirationTime(null).build()));
    }

    @Test
    void missingIssuedAt_returns401() throws Exception {
        assertRejected(IAM.sign(claims().issueTime(null).build()));
    }

    @Test
    void missingSubject_returns401() throws Exception {
        assertRejected(IAM.sign(claims().subject(null).build()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant_id", "role", "email"})
    void missingRequiredClaim_returns401(String claim) throws Exception {
        assertRejected(IAM.sign(claims().claim(claim, null).build()));
    }

    static Stream<Object> malformedTenantIds() {
        return Stream.of(0L, -1L, 1.5, "1", "", "11111111-1111-1111-1111-111111111111", true, List.of(1L));
    }

    @ParameterizedTest
    @MethodSource("malformedTenantIds")
    void malformedTenantId_returns401(Object tenantId) throws Exception {
        assertRejected(IAM.sign(claims().claim("tenant_id", tenantId).build()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUPERADMIN", "user", "USER ADMIN", ""})
    void unknownRole_returns401(String role) throws Exception {
        assertRejected(IAM.sign(claims().claim("role", role).build()));
    }

    @Test
    void roleAsArray_returns401() throws Exception {
        assertRejected(IAM.sign(claims().claim("role", List.of("USER", "ADMIN")).build()));
    }

    // ---------- Helpers ----------

    private void assertBadRequest(String body) throws Exception {
        mvc.perform(createRequest(body, userToken()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(service);
    }

    private void assertRejected(String token) throws Exception {
        mvc.perform(get("/bookings").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    private static JWTClaimsSet.Builder claims() {
        return IAM.validClaims("USER", TENANT_A, USER_EMAIL);
    }

    private static String userToken() {
        return IAM.token("USER", TENANT_A, USER_EMAIL);
    }

    private static String adminToken() {
        return IAM.token("ADMIN", TENANT_A, ADMIN_EMAIL);
    }

    private static MockHttpServletRequestBuilder createRequest(String body, String token) {
        return post("/bookings")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static Booking savedBooking(long id, long roomId, String email) {
        Booking booking = Booking.confirmed(
                TENANT_A, roomId, email, CHECK_IN, CHECK_OUT, Instant.parse("2026-11-03T02:15:30Z"));
        ReflectionTestUtils.setField(booking, "id", id);
        return booking;
    }
}
