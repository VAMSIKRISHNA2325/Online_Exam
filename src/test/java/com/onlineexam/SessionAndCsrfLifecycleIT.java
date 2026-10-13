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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionAndCsrfLifecycleIT {
    private static final String PASSWORD = "CsrfLifecyclePassword-29!";

    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private AccountProvisioningService provisioning;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SessionRegistry registry;

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", IntegrationDb::url);
        properties.add("spring.datasource.username", IntegrationDb::username);
        properties.add("spring.datasource.password", IntegrationDb::password);
    }

    @Test
    void sessionIdAndCsrfRotateAcrossLoginAndLogout() throws Exception {
        String username = "csrf_" + UUID.randomUUID().toString().replace("-", "");
        long userId = provisioning.provision(username, "CSRF Student", Role.STUDENT, PASSWORD);
        Browser browser = new Browser(port, json);
        try {
            String beforeLoginToken = browser.csrf();
            String beforeLoginSession = browser.sessionCookie();
            assertThat(beforeLoginSession).isNotBlank();
            assertThat(browser.me().statusCode()).isEqualTo(401);

            assertThat(browser.login(username, beforeLoginToken).statusCode()).isEqualTo(204);
            String afterLoginSession = browser.sessionCookie();
            assertThat(afterLoginSession).isNotBlank().isNotEqualTo(beforeLoginSession);
            assertThat(browser.me().statusCode()).isEqualTo(200);

            String afterLoginToken = browser.csrf();
            assertThat(afterLoginToken).isNotEqualTo(beforeLoginToken);
            assertThat(browser.logout(beforeLoginToken).statusCode()).isEqualTo(403);
            assertThat(browser.me().statusCode()).isEqualTo(200);
            assertThat(browser.logout(afterLoginToken).statusCode()).isEqualTo(204);
            assertThat(browser.me().statusCode()).isEqualTo(401);

            assertThat(browser.login(username, afterLoginToken).statusCode()).isEqualTo(403);
            assertThat(browser.me().statusCode()).isEqualTo(401);
            String afterLogoutToken = browser.csrf();
            assertThat(afterLogoutToken).isNotEqualTo(afterLoginToken);
            assertThat(browser.login(username, afterLogoutToken).statusCode()).isEqualTo(204);
            assertThat(browser.sessionCookie()).isNotBlank().isNotEqualTo(afterLoginSession);
            assertThat(browser.me().statusCode()).isEqualTo(200);

            assertThat(browser.logout(browser.csrf()).statusCode()).isEqualTo(204);
            assertThat(registry.getAllSessions(
                    ExamPrincipal.from(users.findById(userId).orElseThrow()), false)).isEmpty();
        } finally {
            browser.logoutIfPossible();
            jdbc.update("DELETE FROM security_events WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    private static final class Browser {
        private final URI origin;
        private final ObjectMapper json;
        private final CookieManager cookies;
        private final HttpClient http;

        private Browser(int port, ObjectMapper json) {
            this.origin = URI.create("http://127.0.0.1:" + port);
            this.json = json;
            this.cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
        }

        private String sessionCookie() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> "JSESSIONID".equals(cookie.getName()))
                    .map(HttpCookie::getValue).findFirst().orElse(null);
        }

        private String csrf() throws Exception {
            HttpResponse<String> response = send(HttpRequest.newBuilder(origin.resolve("/api/csrf"))
                    .GET().build());
            assertThat(response.statusCode()).isEqualTo(200);
            return json.readTree(response.body()).get("token").asText();
        }

        private HttpResponse<String> login(String username, String csrfToken) throws Exception {
            String form = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
            return send(HttpRequest.newBuilder(origin.resolve("/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-CSRF-TOKEN", csrfToken)
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build());
        }

        private HttpResponse<String> logout(String csrfToken) throws Exception {
            return send(HttpRequest.newBuilder(origin.resolve("/logout"))
                    .header("X-CSRF-TOKEN", csrfToken)
                    .POST(HttpRequest.BodyPublishers.noBody()).build());
        }

        private HttpResponse<String> me() throws Exception {
            return send(HttpRequest.newBuilder(origin.resolve("/api/me")).GET().build());
        }

        private void logoutIfPossible() {
            try { logout(csrf()); } catch (Exception ignored) { }
        }

        private HttpResponse<String> send(HttpRequest request) throws Exception {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }
}
