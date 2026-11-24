package com.rackwatch.it;

import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Shared base for the integration tests (Failsafe, *IT). Every test
 * class gets a fresh PostgreSQL 16 container; Flyway runs the V1–V3
 * migrations on startup, so the seeded rooms and sensors (V2) are
 * present and the schema matches production. The API key is the
 * test-profile value (application.yml, test profile) — the tests use
 * it as the X-API-Key header. These tests need Docker and Maven
 * Central, so they run in CI and on the developer's machine, never in
 * the unit-test phase (Surefire excludes *IT). */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class PostgresIT {

    /** The key the test profile configures (application.yml). */
    protected static final String API_KEY = "test-api-key-only-for-tests";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
