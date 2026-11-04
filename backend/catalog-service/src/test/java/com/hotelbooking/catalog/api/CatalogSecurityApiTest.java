package com.hotelbooking.catalog.api;

import com.hotelbooking.catalog.security.SecurityConfig;
import com.hotelbooking.catalog.service.CatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CatalogController.class)
@Import(SecurityConfig.class)
class CatalogSecurityApiTest {
    @Autowired
    MockMvc mvc;

    @MockBean
    CatalogService service;

    @MockBean
    JwtDecoder jwtDecoder;

    @Test
    void hotelListRequiresAuthentication() throws Exception {
        mvc.perform(get("/hotels")).andExpect(status().isUnauthorized());
    }

    @Test
    void userCannotPostRoomTypeEvenWhenBodyIsMalformed() throws Exception {
        mvc.perform(post("/hotels/1/room-types")
                        .with(jwt().jwt(token -> token.claim("tenant_id", 1L).claim("role", "USER"))
                                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isForbidden());
        verify(service, never()).createRoomType(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void userMayReadHotels() throws Exception {
        mvc.perform(get("/hotels")
                        .with(jwt().jwt(token -> token.claim("tenant_id", 2L).claim("role", "USER"))
                                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))))
                .andExpect(status().isOk());
        verify(service).hotels(2L);
    }

    @Test
    void adminMalformedBodyGetsBadRequest() throws Exception {
        mvc.perform(post("/hotels/1/room-types")
                        .with(jwt().jwt(token -> token.claim("tenant_id", 1L).claim("role", "ADMIN"))
                                .authorities(List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Suite\",\"capacity\":\"2\",\"basePrice\":100.00,\"totalRooms\":1}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).createRoomType(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void adminCanCreateRoomTypeAndUnknownTenantFieldIsIgnored() throws Exception {
        var roomType = new RoomTypeResponse(7, 1, 1, "Suite", 4,
                new java.math.BigDecimal("2500000.00"), 2);
        org.mockito.Mockito.when(service.createRoomType(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any())).thenReturn(roomType);

        mvc.perform(post("/hotels/1/room-types")
                        .with(jwt().jwt(token -> token.claim("tenant_id", 1L).claim("role", "ADMIN"))
                                .authorities(List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" Suite \",\"capacity\":4,\"basePrice\":2500000.00,\"totalRooms\":2,\"tenantId\":2}"))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.tenantId").value(1));
        verify(service).createRoomType(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void userCannotReadAdminInventory() throws Exception {
        mvc.perform(get("/admin/hotels/1/inventory?checkIn=2026-11-01&checkOut=2026-11-02")
                        .with(jwt().jwt(token -> token.claim("tenant_id", 1L).claim("role", "USER"))
                                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))))
                .andExpect(status().isForbidden());
        verify(service, never()).inventory(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void adminCanUpdatePrice() throws Exception {
        var price = new java.math.BigDecimal("600000.00");
        org.mockito.Mockito.when(service.updatePrice(1L, 1L, price))
                .thenReturn(new RoomTypeResponse(1, 1, 1, "Standard", 2, price, 3));

        mvc.perform(put("/room-types/1/price")
                        .with(jwt().jwt(token -> token.claim("tenant_id", 1L).claim("role", "ADMIN"))
                                .authorities(List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"basePrice\":600000.00}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.basePrice").value(600000.00));
        verify(service).updatePrice(1L, 1L, price);
    }
}
