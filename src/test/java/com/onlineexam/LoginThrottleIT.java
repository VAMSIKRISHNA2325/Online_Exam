package com.onlineexam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineexam.domain.Role;
import com.onlineexam.repository.SecurityEventRepository;
import com.onlineexam.service.AccountProvisioningService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "exam.auth.throttle.cooldown=PT2S")
class LoginThrottleIT {
    private static final String PASSWORD = "ThrottleTestPassword-29!";

    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private AccountProvisioningService provisioning;
    @Autowired private SecurityEventRepository events;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", IntegrationDb::url);
        properties.add("spring.datasource.username", IntegrationDb::username);
        properties.add("spring.datasource.password", IntegrationDb::password);
    }

    @Test
    void repeatedWrongPasswordsThrottleAndCorrectPasswordWorksAfterCooldown() throws Exception {
        String username = "throttle_" + UUID.randomUUID().toString().replace("-", "");
        long userId = provisioning.provision(username, "Throttle Student", Role.STUDENT, PASSWORD);
        String otherName = "throttle_" + UUID.randomUUID().toString().replace("-", "");
        long otherId = provisioning.provision(otherName, "Other Student", Role.STUDENT, PASSWORD);
        Browser blocked = new Browser(port, json);
        Browser other = new Browser(port, json);
        try {
            for (int failure = 0; failure < 5; failure++) {
                assertThat(blocked.login(username, "incorrect-secret").statusCode()).isEqualTo(401);
            }
            HttpResponse<String> limited = blocked.login(username, "incorrect-secret");
            assertThat(limited.statusCode()).isEqualTo(429);
            assertThat(json.readTree(limited.body()).get("code").asText()).isEqualTo("LOGIN_THROTTLED");
            assertThat(limited.headers().firstValue("Retry-After")).isPresent();
            assertThat(limited.body()).doesNotContain(PASSWORD, "incorrect-secret", username,
                    "passwordHash", "JSESSIONID", "_csrf");
            assertThat(blocked.login(username, PASSWORD).statusCode()).isEqualTo(429);
            assertThat(blocked.me().statusCode()).isEqualTo(401);

            // Another account at the same source is not locked out.
            assertThat(other.login(otherName, PASSWORD).statusCode()).isEqualTo(204);
            assertThat(other.me().statusCode()).isEqualTo(200);

            HttpResponse<String> recovered = blocked.login(username, PASSWORD);
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (recovered.statusCode() == 429 && System.nanoTime() < deadline) {
                Thread.sleep(200);
                recovered = blocked.login(username, PASSWORD);
            }
            assertThat(recovered.statusCode()).isEqualTo(204);
            assertThat(blocked.me().statusCode()).isEqualTo(200);
            assertThat(events.findByUser(userId)).isEmpty();
        } finally {
            blocked.logoutIfPossible();
            other.logoutIfPossible();
            deleteUser(userId);
            deleteUser(otherId);
        }
    }

    @Test
    void duplicateSessionConflictsDoNotCountAsCredentialFailures() throws Exception {
        String username = "throttle_" + UUID.randomUUID().toString().replace("-", "");
        long userId = provisioning.provision(username, "Throttle Student", Role.STUDENT, PASSWORD);
        Browser first = new Browser(port, json);
        Browser second = new Browser(port, json);
        try {
            assertThat(first.login(username, PASSWORD).statusCode()).isEqualTo(204);
            for (int duplicate = 0; duplicate < 5; duplicate++) {
                assertThat(second.login(username, PASSWORD).statusCode()).isEqualTo(409);
            }
            assertThat(first.me().statusCode()).isEqualTo(200);
            assertThat(events.findByUser(userId)).hasSize(5)
                    .allSatisfy(event -> assertThat(event.detail())
                            .doesNotContain(PASSWORD, "JSESSIONID", "_csrf"));
            assertThat(first.logout().statusCode()).isEqualTo(204);
            assertThat(second.login(username, PASSWORD).statusCode()).isEqualTo(204);
            assertThat(second.me().statusCode()).isEqualTo(200);
        } finally {
            first.logoutIfPossible();
            second.logoutIfPossible();
            deleteUser(userId);
        }
    }

    private void deleteUser(long userId) {
        jdbc.update("DELETE FROM security_events WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    private static final class Browser {
        private final URI origin;
        private final HttpClient http;
        private final ObjectMapper json;

        private Browser(int port, ObjectMapper json) {
            this.origin = URI.create("http://127.0.0.1:" + port);
            this.http = HttpClient.newBuilder()
                    .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
            this.json = json;
        }

        private String csrf() throws Exception {
            HttpResponse<String> response = send(HttpRequest.newBuilder(origin.resolve("/api/csrf"))
                    .GET().build());
            assertThat(response.statusCode()).isEqualTo(200);
            return json.readTree(response.body()).get("token").asText();
        }

        private HttpResponse<String> login(String username, String password) throws Exception {
            String form = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
            return send(HttpRequest.newBuilder(origin.resolve("/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-CSRF-TOKEN", csrf())
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build());
        }

        private HttpResponse<String> me() throws Exception {
            return send(HttpRequest.newBuilder(origin.resolve("/api/me")).GET().build());
        }

        private HttpResponse<String> logout() throws Exception {
            return send(HttpRequest.newBuilder(origin.resolve("/logout"))
                    .header("X-CSRF-TOKEN", csrf())
                    .POST(HttpRequest.BodyPublishers.noBody()).build());
        }

        private void logoutIfPossible() {
            try { logout(); } catch (Exception ignored) { }
        }

        private HttpResponse<String> send(HttpRequest request) throws Exception {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }
}
