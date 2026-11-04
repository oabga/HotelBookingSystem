package com.hotelbooking.booking.api;

import com.hotelbooking.booking.domain.Booking;
import com.hotelbooking.booking.domain.BookingStatus;
import com.hotelbooking.booking.security.CurrentUser;
import com.hotelbooking.booking.security.SecurityConfig;
import com.hotelbooking.booking.service.BookingNotFoundException;
import com.hotelbooking.booking.service.BookingService;
import com.hotelbooking.booking.service.BookingStateConflictException;
import com.hotelbooking.booking.service.InvalidStayException;
import com.hotelbooking.booking.service.RoomTypeNotFoundException;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

import static com.hotelbooking.booking.support.TestJwtIssuer.TENANT_A;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

    private static final LocalDate CHECK_IN = LocalDate.parse("2026-12-01");
    private static final LocalDate CHECK_OUT = LocalDate.parse("2026-12-03");
    private static final String VALID_BODY = """
            {"roomTypeId":1,"checkIn":"2026-12-01","checkOut":"2026-12-03","roomCount":2}
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
    void userCreatesBooking_returns201WithContractJson() throws Exception {
        when(service.create(USER_A1, 1L, CHECK_IN, CHECK_OUT, 2)).thenReturn(savedBooking(5L));

        mvc.perform(createRequest(VALID_BODY, userToken()))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.tenantId").value(TENANT_A))
                .andExpect(jsonPath("$.roomTypeId").value(1))
                .andExpect(jsonPath("$.userEmail").value(USER_EMAIL))
                .andExpect(jsonPath("$.checkIn").value("2026-12-01"))
                .andExpect(jsonPath("$.checkOut").value("2026-12-03"))
                .andExpect(jsonPath("$.roomCount").value(2))
                .andExpect(jsonPath("$.totalAmount").value(2000000.0))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.paymentExpiresAt").value("2026-11-03T02:30:30Z"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-03T02:15:30Z"))
                .andExpect(jsonPath("$.roomId").doesNotExist())
                .andExpect(jsonPath("$.userSub").doesNotExist());
    }

    @Test
    void identityAndAmountInBodyAreIgnored_comeOnlyFromJwtAndDatabase() throws Exception {
        when(service.create(USER_A1, 1L, CHECK_IN, CHECK_OUT, 2)).thenReturn(savedBooking(5L));
        String body = """
                {"roomTypeId":1,"checkIn":"2026-12-01","checkOut":"2026-12-03","roomCount":2,
                 "tenantId":2,"userEmail":"attacker@example.test","status":"CONFIRMED","totalAmount":1}
                """;

        mvc.perform(createRequest(body, userToken()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(TENANT_A))
                .andExpect(jsonPath("$.userEmail").value(USER_EMAIL))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.totalAmount").value(2000000.0));

        verify(service).create(USER_A1, 1L, CHECK_IN, CHECK_OUT, 2);
    }

    @Test
    void uppercaseEmailInJwt_isNormalizedBeforeReachingService() throws Exception {
        when(service.create(USER_A1, 1L, CHECK_IN, CHECK_OUT, 2)).thenReturn(savedBooking(5L));

        mvc.perform(createRequest(VALID_BODY, IAM.token("USER", TENANT_A, "User-A1@Example.TEST")))
                .andExpect(status().isCreated());

        verify(service).create(USER_A1, 1L, CHECK_IN, CHECK_OUT, 2);
    }

    @Test
    void stayOfExactly30Nights_isAccepted() throws Exception {
        when(service.create(USER_A1, 1L, CHECK_IN, CHECK_IN.plusDays(30), 1)).thenReturn(savedBooking(5L));

        mvc.perform(createRequest(body("2026-12-01", "2026-12-31", "1"), userToken()))
                .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10})
    void roomCountAtLimits_isAccepted(int roomCount) throws Exception {
        when(service.create(USER_A1, 1L, CHECK_IN, CHECK_OUT, roomCount)).thenReturn(savedBooking(5L));

        mvc.perform(createRequest(body("2026-12-01", "2026-12-03", String.valueOf(roomCount)), userToken()))
                .andExpect(status().isCreated());
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
        mvc.perform(createRequest("{\"roomTypeId\":0}", adminToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidTokenWithInvalidBody_returns401BecauseJwtIsCheckedFirst() throws Exception {
        mvc.perform(createRequest("{\"roomTypeId\":0}", "not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"roomTypeId\":", "", "null", "[]"})
    void malformedBody_returns400(String body) throws Exception {
        assertBadRequest(body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "-1", "\"1\"", "1.5", "1.0", "true", "[1]", "99999999999999999999"})
    void invalidRoomTypeId_returns400(String roomTypeId) throws Exception {
        assertBadRequest("{\"roomTypeId\":" + roomTypeId
                + ",\"checkIn\":\"2026-12-01\",\"checkOut\":\"2026-12-03\",\"roomCount\":1}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "-1", "11", "\"1\"", "1.5", "1.0", "true", "[1]", "99999999999"})
    void invalidRoomCount_returns400(String roomCount) throws Exception {
        assertBadRequest(body("2026-12-01", "2026-12-03", roomCount));
    }

    @Test
    void missingRoomCount_returns400() throws Exception {
        assertBadRequest("{\"roomTypeId\":1,\"checkIn\":\"2026-12-01\",\"checkOut\":\"2026-12-03\"}");
    }

    static Stream<String> invalidDates() {
        return Stream.of(
                // Thiếu hoặc null
                "\"checkOut\":\"2026-12-03\"",
                "\"checkIn\":\"2026-12-01\"",
                "\"checkIn\":null,\"checkOut\":\"2026-12-03\"",
                "\"checkIn\":\"2026-12-01\",\"checkOut\":null",
                // Có giờ hoặc múi giờ
                "\"checkIn\":\"2026-12-01T14:00:00\",\"checkOut\":\"2026-12-03\"",
                "\"checkIn\":\"2026-12-01T14:00:00+07:00\",\"checkOut\":\"2026-12-03\"",
                "\"checkIn\":\"2026-12-01\",\"checkOut\":\"2026-12-03T00:00:00Z\"",
                // Sai định dạng hoặc ngày không tồn tại
                "\"checkIn\":\"tomorrow\",\"checkOut\":\"2026-12-03\"",
                "\"checkIn\":\"\",\"checkOut\":\"2026-12-03\"",
                "\"checkIn\":\"01/12/2026\",\"checkOut\":\"2026-12-03\"",
                "\"checkIn\":\"2026-1-1\",\"checkOut\":\"2026-12-03\"",
                "\"checkIn\":\"2026-02-30\",\"checkOut\":\"2026-12-03\"",
                "\"checkIn\":\"2026-12-01\",\"checkOut\":\"+12026-12-03\"",
                // Sai kiểu JSON
                "\"checkIn\":1764572400,\"checkOut\":\"2026-12-03\"",
                "\"checkIn\":\"2026-12-01\",\"checkOut\":[2026,12,3]",
                "\"checkIn\":true,\"checkOut\":\"2026-12-03\"",
                // checkOut không sau checkIn
                "\"checkIn\":\"2026-12-03\",\"checkOut\":\"2026-12-03\"",
                "\"checkIn\":\"2026-12-04\",\"checkOut\":\"2026-12-03\"",
                // Quá 30 đêm
                "\"checkIn\":\"2026-12-01\",\"checkOut\":\"2027-01-01\"");
    }

    @ParameterizedTest
    @MethodSource("invalidDates")
    void invalidStayDates_returns400(String dateFields) throws Exception {
        assertBadRequest("{\"roomTypeId\":1,\"roomCount\":1," + dateFields + "}");
    }

    // ---------- POST /bookings: ánh xạ lỗi nghiệp vụ ----------

    @Test
    void checkInInPast_returns400Problem() throws Exception {
        when(service.create(any(), anyLong(), any(), any(), anyInt()))
                .thenThrow(new InvalidStayException("checkIn must not be in the past"));

        mvc.perform(createRequest(VALID_BODY, userToken()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void roomTypeNotFound_returns404Problem() throws Exception {
        when(service.create(any(), anyLong(), any(), any(), anyInt())).thenThrow(new RoomTypeNotFoundException());

        mvc.perform(createRequest(VALID_BODY, userToken()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Room type not found"))
                .andExpect(jsonPath("$.instance").value("/bookings"));
    }

    @Test
    void notEnoughRooms_returns409Problem() throws Exception {
        when(service.create(any(), anyLong(), any(), any(), anyInt())).thenThrow(new RoomUnavailableException());

        mvc.perform(createRequest(VALID_BODY, userToken()))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Not enough rooms for the requested stay"));
    }

    @Test
    void userNotProvisioned_returns403Problem() throws Exception {
        when(service.create(any(), anyLong(), any(), any(), anyInt())).thenThrow(new UserNotProvisionedException());

        mvc.perform(createRequest(VALID_BODY, userToken()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unexpectedFailure_returns500WithoutInternalDetails() throws Exception {
        when(service.create(any(), anyLong(), any(), any(), anyInt()))
                .thenThrow(new RuntimeException("SQL: insert into booking"));

        mvc.perform(createRequest(VALID_BODY, userToken()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Internal server error"));
    }

    // ---------- POST /bookings/{id}/payment và /cancel ----------

    @Test
    void ownerPays_returns200Confirmed() throws Exception {
        when(service.pay(USER_A1, 7L)).thenReturn(bookingWithStatus(7L, BookingStatus.CONFIRMED));

        mvc.perform(action("/bookings/7/payment", userToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void ownerCancels_returns200Cancelled() throws Exception {
        when(service.cancel(USER_A1, 7L)).thenReturn(bookingWithStatus(7L, BookingStatus.CANCELLED));

        mvc.perform(action("/bookings/7/cancel", userToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/bookings/7/payment", "/bookings/7/cancel"})
    void adminCannotPayOrCancel_returns403(String path) throws Exception {
        mvc.perform(action(path, adminToken())).andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/bookings/0/payment", "/bookings/0/cancel"})
    void adminWithInvalidId_stillReturns403BecauseRoleIsCheckedFirst(String path) throws Exception {
        mvc.perform(action(path, adminToken())).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/bookings/7/payment", "/bookings/7/cancel"})
    void payOrCancelWithoutToken_returns401(String path) throws Exception {
        mvc.perform(post(path)).andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/bookings/0/payment", "/bookings/-1/payment", "/bookings/abc/payment", "/bookings/1.5/payment",
            "/bookings/0/cancel", "/bookings/-1/cancel", "/bookings/abc/cancel", "/bookings/1.5/cancel"
    })
    void payOrCancelWithInvalidId_returns400Problem(String path) throws Exception {
        mvc.perform(action(path, userToken()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(service);
    }

    @Test
    void payNotOwned_returns404Problem() throws Exception {
        when(service.pay(USER_A1, 7L)).thenThrow(new BookingNotFoundException());

        mvc.perform(action("/bookings/7/payment", userToken()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void cancelNotOwned_returns404Problem() throws Exception {
        when(service.cancel(USER_A1, 7L)).thenThrow(new BookingNotFoundException());

        mvc.perform(action("/bookings/7/cancel", userToken()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void payInWrongStatus_returns409Problem() throws Exception {
        when(service.pay(USER_A1, 7L)).thenThrow(new BookingStateConflictException());

        mvc.perform(action("/bookings/7/payment", userToken()))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void cancelInWrongStatus_returns409Problem() throws Exception {
        when(service.cancel(USER_A1, 7L)).thenThrow(new BookingStateConflictException());

        mvc.perform(action("/bookings/7/cancel", userToken()))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // ---------- GET /bookings ----------

    @Test
    void userListsBookings_usesIdentityFromJwt() throws Exception {
        when(service.list(USER_A1)).thenReturn(List.of(savedBooking(1L), savedBooking(2L)));

        mvc.perform(get("/bookings").header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[1].id").value(2));
    }

    @Test
    void adminListsBookings_usesAdminIdentity() throws Exception {
        when(service.list(ADMIN_A)).thenReturn(List.of(savedBooking(1L)));

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
        when(service.get(USER_A1, 7L)).thenReturn(savedBooking(7L));

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

    private static String body(String checkIn, String checkOut, String roomCount) {
        return "{\"roomTypeId\":1,\"checkIn\":\"" + checkIn + "\",\"checkOut\":\"" + checkOut
                + "\",\"roomCount\":" + roomCount + "}";
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

    private static MockHttpServletRequestBuilder action(String path, String token) {
        return post(path).header("Authorization", "Bearer " + token);
    }

    private static Booking savedBooking(long id) {
        Booking booking = Booking.pendingPayment(TENANT_A, 1L, USER_EMAIL, CHECK_IN, CHECK_OUT, 2,
                new BigDecimal("2000000.00"),
                Instant.parse("2026-11-03T02:30:30Z"), Instant.parse("2026-11-03T02:15:30Z"));
        ReflectionTestUtils.setField(booking, "id", id);
        return booking;
    }

    private static Booking bookingWithStatus(long id, BookingStatus status) {
        Booking booking = savedBooking(id);
        ReflectionTestUtils.setField(booking, "status", status);
        return booking;
    }
}
