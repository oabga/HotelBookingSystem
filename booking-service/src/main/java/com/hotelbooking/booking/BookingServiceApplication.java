package com.hotelbooking.booking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.util.TimeZone;

@SpringBootApplication
public class BookingServiceApplication {

    static {
        // Hệ thống lưu và trả thời gian theo UTC. Ngoài ra, driver JDBC gửi múi giờ của JVM cho PostgreSQL:
        // Java trên Windows ở Việt Nam dùng "Asia/Saigon", tên này bị postgres:16 từ chối khi kết nối.
        // Đặt trong static block để có hiệu lực cả khi chạy test, lúc đó main() không được gọi.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(BookingServiceApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
