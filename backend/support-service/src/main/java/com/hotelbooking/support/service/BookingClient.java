package com.hotelbooking.support.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

/**
 * Gọi booking-service bằng chính JWT của người đang thao tác, nên Booking tự kiểm tra role và tenant
 * như mọi request khác, không cần tài khoản dịch vụ riêng. Chỉ Booking được đổi booking và sold.
 */
@Component
public class BookingClient {

    private final RestClient client;

    public BookingClient(@Value("${booking.base-url}") String baseUrl, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    public Quote quote(String token, long bookingId, LocalDate checkIn, LocalDate checkOut) {
        return call(() -> client.get()
                .uri(uri -> uri.path("/bookings/{id}/reschedule-quote")
                        .queryParam("checkIn", checkIn)
                        .queryParam("checkOut", checkOut)
                        .build(bookingId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> throwFor(response.getStatusCode()))
                .body(Quote.class));
    }

    /** Booking bỏ qua lần gọi lại cùng requestId, nên gọi lại sau lỗi giữa chừng là an toàn. */
    public void reschedule(String token, long bookingId, long requestId, LocalDate checkIn, LocalDate checkOut) {
        call(() -> client.post()
                .uri("/bookings/{id}/reschedule", bookingId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("requestId", requestId, "checkIn", checkIn.toString(), "checkOut", checkOut.toString()))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> throwFor(response.getStatusCode()))
                .toBodilessEntity());
    }

    private static void throwFor(HttpStatusCode status) {
        switch (status.value()) {
            case 400 -> throw new InvalidDateChangeException("Invalid dates for this booking");
            case 403 -> throw new UserNotProvisionedException();
            case 404 -> throw new BookingNotFoundException();
            case 409 -> throw new RequestConflictException("Booking cannot be changed to these dates");
            default -> throw new BookingServiceUnavailableException();
        }
    }

    private static <T> T call(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (RestClientException ex) {
            throw new BookingServiceUnavailableException();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Quote(String roomTypeName, LocalDate currentCheckIn, LocalDate currentCheckOut, Integer roomCount,
                        BigDecimal currentTotalAmount, BigDecimal newTotalAmount, boolean available) {
    }
}
