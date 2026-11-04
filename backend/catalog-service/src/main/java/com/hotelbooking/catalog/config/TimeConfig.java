package com.hotelbooking.catalog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class TimeConfig {
    @Bean
    Clock catalogClock() {
        return Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));
    }
}
