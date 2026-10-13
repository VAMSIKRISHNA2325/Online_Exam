package com.onlineexam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineexam.domain.Role;
import com.onlineexam.domain.User;
import com.onlineexam.repository.UserRepository;
import com.onlineexam.service.AccountProvisioningService;
import java.net.URI;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationIT {
    private static final String PASSWORD = "MySqlAuthTestPassword-29!";
    private static final String JDBC_URL = integrationDatabaseUrl();

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private AccountProvisioningService provisioning;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @DynamicPropertySource
    static void integrationDataSource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> JDBC_URL);
        properties.add("spring.datasource.username", () -> envOrDefault("MYSQL_IT_USER", "online_exam"));
        properties.add("spring.datasource.password", AuthenticationIT::integrationPassword);
        properties.add("spring.flyway.enabled", () -> "true");
    }

    @Test
    @Transactional
    void persistedPasswordHashAuthenticatesAndWrongPasswordFails() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("online_exam_it");
        String username = "auth_" + UUID.randomUUID().toString().replace("-", "");
        long id = provisioning.provision(username, "MySQL Student", Role.STUDENT, PASSWORD);
        User stored = users.findByUsername(username).orElseThrow();
        assertThat(stored.id()).isEqualTo(id);
        assertThat(stored.passwordHash()).startsWith("{bcrypt}").doesNotContain(PASSWORD);

        MvcResult csrf = mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession(false);
        String token = json.readTree(csrf.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/login").session(session).param("username", username)
                        .param("password", PASSWORD).param("_csrf", token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.displayName").value("MySQL Student"))
                .andExpect(jsonPath("$.role").value("STUDENT"));

        MvcResult otherCsrf = mvc.perform(get("/api/csrf")).andReturn();
        MockHttpSession otherSession = (MockHttpSession) otherCsrf.getRequest().getSession(false);
        String otherToken = json.readTree(otherCsrf.getResponse().getContentAsString())
                .get("token").asText();
        mvc.perform(post("/login").session(otherSession).param("username", username)
                        .param("password", "incorrect").param("_csrf", otherToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    private static String integrationDatabaseUrl() {
        String url = envOrDefault("MYSQL_IT_DB_URL",
                "jdbc:mysql://127.0.0.1:3307/online_exam_it?connectionTimeZone=UTC");
        if (!url.startsWith("jdbc:mysql://")) {
            throw new IllegalArgumentException("Authentication tests require MySQL online_exam_it");
        }
        URI parsed = URI.create(url.substring("jdbc:".length()));
        if (!"mysql".equals(parsed.getScheme()) || parsed.getHost() == null
                || !"/online_exam_it".equals(parsed.getPath())
                || parsed.getUserInfo() != null || parsed.getFragment() != null) {
            throw new IllegalArgumentException("Authentication tests require MySQL online_exam_it");
        }
        return url;
    }

    private static String integrationPassword() {
        String password = System.getenv("MYSQL_IT_PASSWORD");
        if (password == null || password.isBlank()) password = System.getenv("MYSQL_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("MySQL IT password required");
        }
        return password;
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
