package com.hotelbooking.support.api;

import com.hotelbooking.support.domain.SupportTicket;
import com.hotelbooking.support.domain.TicketMessage;
import com.hotelbooking.support.domain.TicketStatus;
import com.hotelbooking.support.security.CurrentUser;
import com.hotelbooking.support.security.SecurityConfig;
import com.hotelbooking.support.service.BookingNotFoundException;
import com.hotelbooking.support.service.SupportService;
import com.hotelbooking.support.service.SupportService.TicketPage;
import com.hotelbooking.support.service.SupportService.TicketView;
import com.hotelbooking.support.service.TicketNotFoundException;
import com.hotelbooking.support.service.TicketStateConflictException;
import com.hotelbooking.support.service.UserNotProvisionedException;
import com.hotelbooking.support.testing.TestJwtIssuer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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

import java.time.Instant;
import java.util.List;

import static com.hotelbooking.support.testing.TestJwtIssuer.TENANT_A;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Kiểm tra lớp HTTP và bảo mật. Token là JWT thật, không dùng .with(jwt()). */
@WebMvcTest(SupportController.class)
@Import(SecurityConfig.class)
class SupportApiTest {

    private static final TestJwtIssuer IAM = new TestJwtIssuer();
    private static final String USER_EMAIL = "user-a1@example.test";
    private static final CurrentUser USER_A1 = new CurrentUser(TENANT_A, USER_EMAIL, false);
    private static final Instant NOW = Instant.parse("2026-11-04T05:00:00Z");
    private static final String VALID_TICKET = """
            {"bookingId":5,"subject":"Cần hỗ trợ","description":"Đổi giờ đến"}
            """;

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("security.jwt.issuer", () -> TestJwtIssuer.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", IAM::jwkSetUri);
    }

    @Autowired
    private MockMvc mvc;

    @MockBean
    private SupportService support;

    private static String userToken() {
        return "Bearer " + IAM.token("USER", TENANT_A, USER_EMAIL);
    }

    private static String adminToken() {
        return "Bearer " + IAM.token("ADMIN", TENANT_A, "admin-a@example.test");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String token, String body) {
        return request.header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static TicketView view(TicketStatus status) {
        SupportTicket ticket = SupportTicket.open(TENANT_A, 11, 5L, "Cần hỗ trợ", "Đổi giờ đến", NOW);
        ReflectionTestUtils.setField(ticket, "id", 9L);
        ReflectionTestUtils.setField(ticket, "status", status);
        return new TicketView(ticket, USER_EMAIL, List.of());
    }

    private static TicketMessage message() {
        TicketMessage message = new TicketMessage(TENANT_A, 9, 11, "USER", "Xin chào", NOW);
        ReflectionTestUtils.setField(message, "id", 3L);
        return message;
    }

    // ---------- Xác thực và phân quyền ----------

    @Test
    void everyRouteRejectsMissingOrInvalidToken() throws Exception {
        mvc.perform(get("/support/tickets")).andExpect(status().isUnauthorized());
        mvc.perform(get("/support/tickets/1")).andExpect(status().isUnauthorized());
        mvc.perform(post("/support/tickets").contentType(MediaType.APPLICATION_JSON).content(VALID_TICKET))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/support/tickets").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(support);
    }

    @Test
    void tokenWithWrongIssuerOrMissingTenantIsRejected() throws Exception {
        String wrongIssuer = IAM.sign(IAM.validClaims("USER", TENANT_A, USER_EMAIL).issuer("http://evil.test").build());
        String noTenant = IAM.sign(IAM.validClaims("USER", TENANT_A, USER_EMAIL).claim("tenant_id", null).build());
        String stringTenant = IAM.sign(IAM.validClaims("USER", TENANT_A, USER_EMAIL).claim("tenant_id", "1").build());
        String untrusted = IAM.signWithUntrustedKey(IAM.validClaims("USER", TENANT_A, USER_EMAIL).build());
        for (String token : List.of(wrongIssuer, noTenant, stringTenant, untrusted)) {
            mvc.perform(get("/support/tickets").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(support);
    }

    @Test
    void adminCannotCreateTicketEvenWithBadBody() throws Exception {
        mvc.perform(json(post("/support/tickets"), adminToken(), VALID_TICKET)).andExpect(status().isForbidden());
        mvc.perform(json(post("/support/tickets"), adminToken(), "{bad")).andExpect(status().isForbidden());
        verifyNoInteractions(support);
    }

    @Test
    void userCannotChangeStatusEvenWithBadBody() throws Exception {
        mvc.perform(json(patch("/support/tickets/1/status"), userToken(), "{\"status\":\"CLOSED\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(json(patch("/support/tickets/1/status"), userToken(), "{bad")).andExpect(status().isForbidden());
        verifyNoInteractions(support);
    }

    // ---------- Tạo ticket ----------

    @Test
    void createReturns201FromJwtIdentity() throws Exception {
        when(support.create(USER_A1, 5L, "Cần hỗ trợ", "Đổi giờ đến")).thenReturn(view(TicketStatus.OPEN));
        mvc.perform(json(post("/support/tickets"), userToken(), VALID_TICKET))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.bookingId").value(5))
                .andExpect(jsonPath("$.createdAt").value("2026-11-04T05:00:00Z"))
                .andExpect(jsonPath("$.closedAt").isEmpty());
    }

    @Test
    void bookingIdIsOptionalAndTextIsTrimmed() throws Exception {
        when(support.create(USER_A1, null, "Hỏi đáp", "Nội dung")).thenReturn(view(TicketStatus.OPEN));
        mvc.perform(json(post("/support/tickets"), userToken(), """
                        {"subject":"  Hỏi đáp ","description":" Nội dung\\n"}
                        """))
                .andExpect(status().isCreated());
        verify(support).create(USER_A1, null, "Hỏi đáp", "Nội dung");
    }

    @Test
    void unknownFieldsCannotChangeTenantOrStatus() throws Exception {
        when(support.create(USER_A1, 5L, "Cần hỗ trợ", "Đổi giờ đến")).thenReturn(view(TicketStatus.OPEN));
        mvc.perform(json(post("/support/tickets"), userToken(), """
                        {"bookingId":5,"subject":"Cần hỗ trợ","description":"Đổi giờ đến",
                         "tenantId":2,"status":"CLOSED","requesterUserId":99}
                        """))
                .andExpect(status().isCreated());
        verify(support).create(USER_A1, 5L, "Cần hỗ trợ", "Đổi giờ đến");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{bad",
            "{}",
            "{\"subject\":\"s\"}",
            "{\"description\":\"d\"}",
            "{\"subject\":\"  \",\"description\":\"d\"}",
            "{\"subject\":\"s\",\"description\":\"\"}",
            "{\"subject\":123,\"description\":\"d\"}",
            "{\"subject\":\"s\",\"description\":true}",
            "{\"subject\":\"dòng 1\\ndòng 2\",\"description\":\"d\"}",
            "{\"subject\":\"<b>đậm</b>\",\"description\":\"d\"}",
            "{\"subject\":\"s\",\"description\":\"<script>alert(1)</script>\"}",
            "{\"subject\":\"s\",\"description\":\"a\\u0000b\"}",
            "{\"bookingId\":\"5\",\"subject\":\"s\",\"description\":\"d\"}",
            "{\"bookingId\":1.5,\"subject\":\"s\",\"description\":\"d\"}",
            "{\"bookingId\":0,\"subject\":\"s\",\"description\":\"d\"}",
            "{\"bookingId\":-1,\"subject\":\"s\",\"description\":\"d\"}"
    })
    void invalidTicketBodyIs400(String body) throws Exception {
        mvc.perform(json(post("/support/tickets"), userToken(), body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(support);
    }

    @Test
    void textLengthLimitsAreEnforced() throws Exception {
        when(support.create(any(), any(), anyString(), anyString())).thenReturn(view(TicketStatus.OPEN));
        String ok = "{\"subject\":\"" + "a".repeat(200) + "\",\"description\":\"" + "b".repeat(4000) + "\"}";
        mvc.perform(json(post("/support/tickets"), userToken(), ok)).andExpect(status().isCreated());
        String longSubject = "{\"subject\":\"" + "a".repeat(201) + "\",\"description\":\"d\"}";
        mvc.perform(json(post("/support/tickets"), userToken(), longSubject)).andExpect(status().isBadRequest());
        String longBody = "{\"subject\":\"s\",\"description\":\"" + "b".repeat(4001) + "\"}";
        mvc.perform(json(post("/support/tickets"), userToken(), longBody)).andExpect(status().isBadRequest());
    }

    @Test
    void lessThanSignInPlainTextIsAllowed() throws Exception {
        when(support.create(any(), any(), anyString(), anyString())).thenReturn(view(TicketStatus.OPEN));
        mvc.perform(json(post("/support/tickets"), userToken(), """
                        {"subject":"Giá < 500000","description":"a < 5 và 3 > 2"}
                        """))
                .andExpect(status().isCreated());
    }

    @Test
    void foreignBookingIs404AndUnprovisionedIs403() throws Exception {
        when(support.create(any(), any(), anyString(), anyString())).thenThrow(new BookingNotFoundException());
        mvc.perform(json(post("/support/tickets"), userToken(), VALID_TICKET))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        org.mockito.Mockito.reset(support);
        when(support.create(any(), any(), anyString(), anyString())).thenThrow(new UserNotProvisionedException());
        mvc.perform(json(post("/support/tickets"), userToken(), VALID_TICKET)).andExpect(status().isForbidden());
    }

    @Test
    void unexpectedErrorDoesNotLeakDetails() throws Exception {
        when(support.create(any(), any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("SELECT secret FROM app_user"));
        mvc.perform(json(post("/support/tickets"), userToken(), VALID_TICKET))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Internal server error"));
    }

    // ---------- Danh sách và chi tiết ----------

    @Test
    void listIsPaginatedWithDefaults() throws Exception {
        when(support.list(USER_A1, 0, 20)).thenReturn(new TicketPage(List.of(view(TicketStatus.OPEN)), 0, 20, 1, 1));
        mvc.perform(get("/support/tickets").header("Authorization", userToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(9))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=abc", "size=1.5"})
    void invalidPagingIs400(String query) throws Exception {
        mvc.perform(get("/support/tickets?" + query).header("Authorization", userToken()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(support);
    }

    @Test
    void detailIncludesMessages() throws Exception {
        SupportTicket ticket = view(TicketStatus.OPEN).ticket();
        when(support.get(USER_A1, 9)).thenReturn(new TicketView(ticket, USER_EMAIL, List.of(message())));
        mvc.perform(get("/support/tickets/9").header("Authorization", userToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].body").value("Xin chào"))
                .andExpect(jsonPath("$.messages[0].authorRole").value("USER"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc"})
    void invalidTicketIdIs400(String id) throws Exception {
        mvc.perform(get("/support/tickets/" + id).header("Authorization", userToken()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(support);
    }

    @Test
    void missingTicketIs404() throws Exception {
        when(support.get(USER_A1, 9)).thenThrow(new TicketNotFoundException());
        mvc.perform(get("/support/tickets/9").header("Authorization", userToken())).andExpect(status().isNotFound());
    }

    // ---------- Tin nhắn ----------

    @Test
    void addMessageReturns201AndIgnoresClaimedAuthor() throws Exception {
        when(support.addMessage(USER_A1, 9, "Xin chào")).thenReturn(message());
        mvc.perform(json(post("/support/tickets/9/messages"), userToken(), """
                        {"body":" Xin chào ","authorUserId":999,"authorRole":"ADMIN"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorUserId").value(11))
                .andExpect(jsonPath("$.authorRole").value("USER"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"body\":\"  \"}", "{\"body\":5}", "{\"body\":\"<img src=x>\"}", "{bad"})
    void invalidMessageIs400(String body) throws Exception {
        mvc.perform(json(post("/support/tickets/9/messages"), userToken(), body)).andExpect(status().isBadRequest());
        verifyNoInteractions(support);
    }

    @Test
    void messageOnClosedTicketIs409() throws Exception {
        when(support.addMessage(any(), anyLong(), anyString())).thenThrow(new TicketStateConflictException("closed"));
        mvc.perform(json(post("/support/tickets/9/messages"), userToken(), "{\"body\":\"x\"}"))
                .andExpect(status().isConflict());
    }

    // ---------- Trạng thái ----------

    @Test
    void adminChangesStatus() throws Exception {
        when(support.changeStatus(new CurrentUser(TENANT_A, "admin-a@example.test", true), 9, TicketStatus.IN_PROGRESS))
                .thenReturn(view(TicketStatus.IN_PROGRESS));
        mvc.perform(json(patch("/support/tickets/9/status"), adminToken(), "{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"status\":\"DONE\"}", "{\"status\":\"open\"}", "{\"status\":1}", "{\"status\":null}", "{bad"})
    void invalidStatusIs400(String body) throws Exception {
        mvc.perform(json(patch("/support/tickets/9/status"), adminToken(), body)).andExpect(status().isBadRequest());
        verifyNoInteractions(support);
    }

    @Test
    void invalidTransitionIs409() throws Exception {
        when(support.changeStatus(any(), anyLong(), any())).thenThrow(new TicketStateConflictException("no"));
        mvc.perform(json(patch("/support/tickets/9/status"), adminToken(), "{\"status\":\"CLOSED\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void listNeverReachesServiceWithoutPagingArgumentsTypeError() throws Exception {
        when(support.list(any(), anyInt(), anyInt())).thenReturn(new TicketPage(List.of(), 2, 5, 0, 0));
        mvc.perform(get("/support/tickets?page=2&size=5").header("Authorization", adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page").value(2));
    }
}
