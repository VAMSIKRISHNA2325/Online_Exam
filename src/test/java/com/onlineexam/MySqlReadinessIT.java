package com.onlineexam;

import static org.assertj.core.api.Assertions.assertThat;

import com.onlineexam.controller.ReadinessController;
import java.net.URI;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class MySqlReadinessIT {
    private static final String DEFAULT_URL =
            "jdbc:mysql://127.0.0.1:3307/online_exam_it?connectionTimeZone=UTC";
    private static final String JDBC_URL = integrationDatabaseUrl();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TestRestTemplate http;

    @DynamicPropertySource
    static void integrationDataSource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> JDBC_URL);
        properties.add("spring.datasource.username", () -> envOrDefault("MYSQL_IT_USER", "online_exam"));
        properties.add("spring.datasource.password", MySqlReadinessIT::integrationPassword);
        properties.add("spring.flyway.enabled", () -> "true");
    }

    @Test
    void connectsToTheDisposableMySqlSchemaAndServesReadiness() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("online_exam_it");
        ResponseEntity<ReadinessController.Readiness> response =
                http.getForEntity("/api/ready", ReadinessController.Readiness.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("UP");
        assertThat(response.getBody().serverNow()).isNotNull();
    }

    private static String integrationDatabaseUrl() {
        String url = envOrDefault("MYSQL_IT_DB_URL", DEFAULT_URL);
        if (!url.startsWith("jdbc:mysql://")) {
            throw new IllegalArgumentException("MYSQL_IT_DB_URL must be a MySQL JDBC URL for online_exam_it");
        }
        URI parsed = URI.create(url.substring("jdbc:".length()));
        if (!"mysql".equals(parsed.getScheme()) || parsed.getHost() == null
                || !"/online_exam_it".equals(parsed.getPath())
                || parsed.getUserInfo() != null || parsed.getFragment() != null) {
            throw new IllegalArgumentException("MYSQL_IT_DB_URL must identify only online_exam_it");
        }
        return url;
    }

    private static String integrationPassword() {
        String password = System.getenv("MYSQL_IT_PASSWORD");
        if (password == null || password.isBlank()) {
            password = System.getenv("MYSQL_PASSWORD");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Set MYSQL_IT_PASSWORD or MYSQL_PASSWORD for the real MySQL integration test");
        }
        return password;
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
