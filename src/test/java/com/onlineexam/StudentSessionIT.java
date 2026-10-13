package com.onlineexam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineexam.domain.Role;
import com.onlineexam.domain.SecurityEvent;
import com.onlineexam.repository.SecurityEventRepository;
import com.onlineexam.repository.UserRepository;
import com.onlineexam.security.ExamPrincipal;
import com.onlineexam.service.AccountProvisioningService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudentSessionIT {
    private static final String PASSWORD = "SessionTestPassword-29!";
    private static final String JDBC_URL = IntegrationDb.url();

    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private AccountProvisioningService provisioning;
    @Autowired private UserRepository users;
    @Autowired private SecurityEventRepository events;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SessionRegistry registry;

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> JDBC_URL);
        properties.add("spring.datasource.username", IntegrationDb::username);
        properties.add("spring.datasource.password", IntegrationDb::password);
    }

    @Test
    void secondStudentLoginIsDeniedAndLogoutReleasesTheFirstSession() throws Exception {
        long userId = provision(Role.STUDENT);
        Browser first = new Browser(port, json);
        Browser second = new Browser(port, json);
        try {
            String beforeLogin = first.sessionCookie();
            assertThat(beforeLogin).isNull();
            assertThat(first.login(users.findById(userId).orElseThrow().username()).statusCode()).isEqualTo(204);
            assertThat(first.me().statusCode()).isEqualTo(200);

            assertThat(second.login(users.findById(userId).orElseThrow().username()).statusCode()).isEqualTo(409);
            assertThat(second.me().statusCode()).isEqualTo(401);
            assertThat(first.me().statusCode()).isEqualTo(200);
            assertOneSanitizedDuplicateEvent(userId);

            assertThat(first.logout().statusCode()).isEqualTo(204);
            assertThat(first.me().statusCode()).isEqualTo(401);
            assertThat(registry.getAllSessions(ExamPrincipal.from(users.findById(userId).orElseThrow()), false))
                    .isEmpty();
            assertThat(second.login(users.findById(userId).orElseThrow().username()).statusCode()).isEqualTo(204);
            assertThat(second.me().statusCode()).isEqualTo(200);
        } finally {
            second.logoutIfPossible();
            deleteUser(userId);
        }
    }

    @Test
    void simultaneousStudentLoginsHaveExactlyOneWinner() throws Exception {
        long userId = provision(Role.STUDENT);
        String username = users.findById(userId).orElseThrow().username();
        Browser first = new Browser(port, json);
        Browser second = new Browser(port, json);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            String firstToken = first.csrf();
            String secondToken = second.csrf();
            CountDownLatch start = new CountDownLatch(1);
            Future<HttpResponse<String>> one = workers.submit(() -> {
                start.await();
                return first.loginWithToken(username, firstToken);
            });
            Future<HttpResponse<String>> two = workers.submit(() -> {
                start.await();
                return second.loginWithToken(username, secondToken);
            });
            start.countDown();
            int firstStatus = one.get().statusCode();
            int secondStatus = two.get().statusCode();
            assertThat(List.of(firstStatus, secondStatus)).containsExactlyInAnyOrder(204, 409);
            Browser winner = firstStatus == 204 ? first : second;
            Browser loser = firstStatus == 409 ? first : second;
            assertThat(winner.me().statusCode()).isEqualTo(200);
            assertThat(loser.me().statusCode()).isEqualTo(401);
            assertOneSanitizedDuplicateEvent(userId);
            assertThat(winner.logout().statusCode()).isEqualTo(204);
        } finally {
            workers.shutdownNow();
            deleteUser(userId);
        }
    }

    @Test
    void instructorsCanUseTwoSessions() throws Exception {
        long userId = provision(Role.INSTRUCTOR);
        String username = users.findById(userId).orElseThrow().username();
        Browser first = new Browser(port, json);
        Browser second = new Browser(port, json);
        try {
            assertThat(first.login(username).statusCode()).isEqualTo(204);
            assertThat(second.login(username).statusCode()).isEqualTo(204);
            assertThat(first.me().statusCode()).isEqualTo(200);
            assertThat(second.me().statusCode()).isEqualTo(200);
            assertThat(events.findByUser(userId)).isEmpty();
        } finally {
            first.logoutIfPossible();
            second.logoutIfPossible();
            deleteUser(userId);
        }
    }

    private long provision(Role role) {
        String username = "session_" + UUID.randomUUID().toString().replace("-", "");
        return provisioning.provision(username, "Session Test", role, PASSWORD);
    }

    private void assertOneSanitizedDuplicateEvent(long userId) {
        List<SecurityEvent> recorded = events.findByUser(userId);
        assertThat(recorded).hasSize(1);
        assertThat(recorded.getFirst().eventType()).isEqualTo("DUPLICATE_LOGIN");
        assertThat(recorded.getFirst().detail()).isEqualTo("Second authenticated login denied")
                .doesNotContain(PASSWORD, "JSESSIONID", "_csrf");
        assertThat(recorded.getFirst().examId()).isNull();
        assertThat(recorded.getFirst().attemptId()).isNull();
    }

    private void deleteUser(long id) {
        jdbc.update("DELETE FROM security_events WHERE user_id = ?", id);
        jdbc.update("DELETE FROM users WHERE id = ?", id);
    }

    private static final class Browser {
        private final URI origin;
        private final HttpClient client;
        private final CookieManager cookies;
        private final ObjectMapper json;

        private Browser(int port, ObjectMapper json) {
            this.origin = URI.create("http://127.0.0.1:" + port);
            this.cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            this.client = HttpClient.newBuilder().cookieHandler(cookies).build();
            this.json = json;
        }

        private String sessionCookie() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> "JSESSIONID".equals(cookie.getName()))
                    .map(java.net.HttpCookie::getValue).findFirst().orElse(null);
        }

        private String csrf() throws Exception {
            HttpResponse<String> result = send(HttpRequest.newBuilder(origin.resolve("/api/csrf"))
                    .GET().build());
            assertThat(result.statusCode()).isEqualTo(200);
            return json.readTree(result.body()).get("token").asText();
        }

        private HttpResponse<String> login(String username) throws Exception {
            return loginWithToken(username, csrf());
        }

        private HttpResponse<String> loginWithToken(String username, String token) throws Exception {
            String body = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
            return send(HttpRequest.newBuilder(origin.resolve("/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-CSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build());
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
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }
}
