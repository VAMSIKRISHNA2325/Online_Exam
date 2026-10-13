package com.onlineexam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineexam.domain.Role;
import com.onlineexam.repository.UserRepository;
import com.onlineexam.security.ExamPrincipal;
import com.onlineexam.service.AccountProvisioningService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudentSessionExpiryIT {
    private static final String PASSWORD = "ExpiryTestPassword-29!";

    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private AccountProvisioningService provisioning;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SessionRegistry registry;
    @Autowired private ServletWebServerApplicationContext webContext;

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", IntegrationDb::url);
        properties.add("spring.datasource.username", IntegrationDb::username);
        properties.add("spring.datasource.password", IntegrationDb::password);
    }

    @Test
    void lostCookieCanReauthenticateAfterInactiveSessionExpires() throws Exception {
        String username = "expiry_" + UUID.randomUUID().toString().replace("-", "");
        long id = provisioning.provision(username, "Expiry Test", Role.STUDENT, PASSWORD);
        Browser first = new Browser(port, json);
        Browser second = new Browser(port, json);
        try {
            assertThat(first.login(username).statusCode()).isEqualTo(204);
            assertThat(first.me().statusCode()).isEqualTo(200);
            assertThat(registry.getAllSessions(ExamPrincipal.from(users.findById(id).orElseThrow()), false))
                    .hasSize(1);
            Session original = setSingleSessionIdleInterval(first.sessionCookie(), 1);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (original.isValid() && System.nanoTime() < deadline) Thread.sleep(100);
            assertThat(original.isValid()).isFalse();
            assertThat(first.me().statusCode()).isEqualTo(401);
            assertThat(registry.getAllSessions(ExamPrincipal.from(users.findById(id).orElseThrow()), false))
                    .isEmpty();
            assertThat(second.login(username).statusCode()).isEqualTo(204);
            assertThat(second.me().statusCode()).isEqualTo(200);
        } finally {
            second.logoutIfPossible();
            jdbc.update("DELETE FROM security_events WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    private Session setSingleSessionIdleInterval(String sessionId, int seconds) throws Exception {
        TomcatWebServer webServer = (TomcatWebServer) webContext.getWebServer();
        for (Container child : webServer.getTomcat().getHost().findChildren()) {
            if (child instanceof Context context) {
                Session session = context.getManager().findSession(sessionId);
                if (session != null) {
                    session.setMaxInactiveInterval(seconds);
                    assertThat(session.getMaxInactiveInterval()).isEqualTo(seconds);
                    return session;
                }
            }
        }
        throw new IllegalStateException("Test HTTP session not found in Tomcat");
    }

    private static final class Browser {
        private final URI origin;
        private final ObjectMapper json;
        private final HttpClient http;
        private final CookieManager cookies;

        private Browser(int port, ObjectMapper json) {
            this.origin = URI.create("http://127.0.0.1:" + port);
            this.json = json;
            this.cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
        }

        private String sessionCookie() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> "JSESSIONID".equals(cookie.getName()))
                    .map(HttpCookie::getValue).findFirst().orElseThrow();
        }

        private String csrf() throws Exception {
            HttpResponse<String> response = request(HttpRequest.newBuilder(origin.resolve("/api/csrf"))
                    .GET().build());
            assertThat(response.statusCode()).isEqualTo(200);
            return json.readTree(response.body()).get("token").asText();
        }

        private HttpResponse<String> login(String username) throws Exception {
            String form = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
            return request(HttpRequest.newBuilder(origin.resolve("/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-CSRF-TOKEN", csrf())
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build());
        }

        private HttpResponse<String> me() throws Exception {
            return request(HttpRequest.newBuilder(origin.resolve("/api/me")).GET().build());
        }

        private void logoutIfPossible() {
            try {
                request(HttpRequest.newBuilder(origin.resolve("/logout"))
                        .header("X-CSRF-TOKEN", csrf())
                        .POST(HttpRequest.BodyPublishers.noBody()).build());
            } catch (Exception ignored) { }
        }

        private HttpResponse<String> request(HttpRequest request) throws Exception {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }
}
