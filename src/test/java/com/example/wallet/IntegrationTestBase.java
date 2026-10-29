package com.example.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Uses the Postgres given by TEST_DB_URL (docker compose "tests" service) or, if unset,
 * starts a throw-away Postgres 16 with Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {
    private static PostgreSQLContainer<?> container;

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        String url = System.getenv("TEST_DB_URL");
        if (url != null) {
            r.add("spring.datasource.url", () -> url);
            r.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USER", "wallet"));
            r.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", "wallet"));
        } else {
            synchronized (IntegrationTestBase.class) {
                if (container == null) { container = new PostgreSQLContainer<>("postgres:16"); container.start(); }
            }
            r.add("spring.datasource.url", container::getJdbcUrl);
            r.add("spring.datasource.username", container::getUsername);
            r.add("spring.datasource.password", container::getPassword);
        }
    }

    @Autowired protected TestRestTemplate rest;
    @Autowired protected ObjectMapper om;
    @Autowired protected JdbcTemplate jdbc;
    protected Api api;

    @BeforeEach
    void initApi() {
        api = new Api(rest, om);
        jdbc.update("UPDATE risk_rules SET enabled = true");
    }

    protected long sumEntries(String direction, String where, Object... args) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM ledger_entries WHERE direction = ? AND " + where,
            Long.class, prepend(direction, args));
    }

    private static Object[] prepend(Object first, Object[] rest) {
        Object[] a = new Object[rest.length + 1];
        a[0] = first;
        System.arraycopy(rest, 0, a, 1, rest.length);
        return a;
    }
}
