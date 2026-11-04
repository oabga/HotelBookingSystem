package com.hotelbooking.catalog;

import com.hotelbooking.catalog.repository.CatalogRepository;
import com.hotelbooking.catalog.api.HotelResponse;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CatalogMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("hotelbooking")
            .withUsername("hotelbooking")
            .withPassword("hotelbooking");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CatalogRepository repository;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(iamMigrationLocation())
                .table("flyway_schema_history_iam")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static String iamMigrationLocation() {
        return List.of(
                        Path.of("../iam-service/src/main/resources/db/migration"),
                        Path.of("iam-service/src/main/resources/db/migration"),
                        Path.of("backend/iam-service/src/main/resources/db/migration"))
                .stream()
                .filter(Files::isDirectory)
                .findFirst()
                .map(path -> "filesystem:" + path.toAbsolutePath())
                .orElseThrow(() -> new IllegalStateException("Could not locate IAM migration directory"));
    }

    @Test
    void catalogMigrationsSeedExpectedTenantScopedRows() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hotel", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM room_type", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM room_inventory", Integer.class)).isEqualTo(552);
        assertThat(jdbc.queryForList("SELECT DISTINCT sold FROM room_inventory", Integer.class)).containsExactly(0);
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history_catalog WHERE success", String.class))
                .contains("1", "2");
    }

    @Test
    void databaseRejectsInventoryOversell() {
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE room_inventory SET sold = total + 1
                WHERE tenant_id = 1 AND room_type_id = 5 AND stay_date = DATE '2026-11-24'
                """ )).hasMessageContaining("chk_inventory_not_oversold");
    }

    @Test
    void availabilityRequiresEveryNightAndStaysWithinTenant() {
        assertThat(repository.findHotels(1)).extracting(HotelResponse::id).containsExactly(1L, 3L);
        assertThat(repository.findHotels(2)).extracting(HotelResponse::id).containsExactly(2L);

        var checkIn = java.time.LocalDate.of(2026, 11, 24);
        var checkOut = java.time.LocalDate.of(2026, 11, 26);
        assertThat(repository.findAvailability(1, 1, checkIn, checkOut, 2))
                .extracting(CatalogRepository.AvailabilityRow::id).containsExactly(1L, 2L);

        try {
            jdbc.update("UPDATE room_inventory SET sold = total WHERE tenant_id = 1 AND room_type_id = 2 AND stay_date = DATE '2026-11-25'");
            assertThat(repository.findAvailability(1, 1, checkIn, checkOut, 2))
                    .extracting(CatalogRepository.AvailabilityRow::id).containsExactly(1L);
        } finally {
            jdbc.update("UPDATE room_inventory SET sold = 0 WHERE tenant_id = 1 AND room_type_id = 2 AND stay_date = DATE '2026-11-25'");
        }

        assertThat(repository.findAvailability(1, 1, java.time.LocalDate.of(2027, 1, 31),
                java.time.LocalDate.of(2027, 2, 2), 2)).isEmpty();
        assertThat(repository.hotelExists(1, 2)).isFalse();
    }
}
