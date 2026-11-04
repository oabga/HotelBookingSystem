package com.hotelbooking.support.testing;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Đóng vai booking-service qua HTTP thật, để test được mọi phản hồi mà không phải chạy service đó. */
public final class FakeBookingService {

    public record Call(String method, String path, String authorization, String body) {
    }

    private final HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile int quoteStatus = 200;
    private volatile int rescheduleStatus = 200;
    private volatile boolean available = true;

    public FakeBookingService() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        server.createContext("/bookings", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(new Call(exchange.getRequestMethod(), path,
                    exchange.getRequestHeaders().getFirst("Authorization"), body));
            boolean quote = path.endsWith("/reschedule-quote");
            int status = quote ? quoteStatus : rescheduleStatus;
            String json = quote && status == 200 ? """
                    {"bookingId":1,"roomTypeId":1,"roomTypeName":"Standard","currentCheckIn":"2026-12-01",
                     "currentCheckOut":"2026-12-03","checkIn":"2026-12-10","checkOut":"2026-12-13","nights":3,
                     "roomCount":2,"currentTotalAmount":2000000.00,"newTotalAmount":3000000.00,"available":%s}
                    """.formatted(available) : "{}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public void reset() {
        calls.clear();
        quoteStatus = 200;
        rescheduleStatus = 200;
        available = true;
    }

    public void quoteReturns(int status) {
        quoteStatus = status;
    }

    public void rescheduleReturns(int status) {
        rescheduleStatus = status;
    }

    public void noRoomsAvailable() {
        available = false;
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public List<Call> rescheduleCalls() {
        return calls.stream().filter(call -> call.path().endsWith("/reschedule")).toList();
    }

    public void stop() {
        server.stop(0);
    }
}
