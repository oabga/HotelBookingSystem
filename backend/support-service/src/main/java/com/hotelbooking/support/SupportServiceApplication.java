package com.hotelbooking.support;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.util.TimeZone;

@SpringBootApplication
public class SupportServiceApplication {

    static {
        // Hệ thống lưu và trả thời gian theo UTC. Ngoài ra, driver JDBC gửi múi giờ của JVM cho PostgreSQL:
        // Java trên Windows ở Việt Nam dùng "Asia/Saigon", tên này bị postgres:16 từ chối khi kết nối.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(SupportServiceApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
