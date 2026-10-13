package com.onlineexam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineexam.domain.Answer;
import com.onlineexam.domain.Attempt;
import com.onlineexam.domain.AttemptStatus;
import com.onlineexam.domain.Exam;
import com.onlineexam.domain.ExamStatus;
import com.onlineexam.domain.Question;
import com.onlineexam.domain.QuestionOption;
import com.onlineexam.domain.Role;
import com.onlineexam.domain.SecurityEvent;
import com.onlineexam.repository.AnswerRepository;
import com.onlineexam.repository.AttemptRepository;
import com.onlineexam.repository.ExamRepository;
import com.onlineexam.repository.QuestionOptionRepository;
import com.onlineexam.repository.QuestionRepository;
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
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionRecoveryIT {
    private static final String PASSWORD = "RecoveryTestPassword-29!";

    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private AccountProvisioningService provisioning;
    @Autowired private UserRepository users;
    @Autowired private ExamRepository exams;
    @Autowired private QuestionRepository questions;
    @Autowired private QuestionOptionRepository options;
    @Autowired private AttemptRepository attempts;
    @Autowired private AnswerRepository answers;
    @Autowired private SecurityEventRepository events;
    @Autowired private SessionRegistry registry;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", IntegrationDb::url);
        properties.add("spring.datasource.username", IntegrationDb::username);
        properties.add("spring.datasource.password", IntegrationDb::password);
    }

    @Test
    void ownerRecoveryExpiresOldSessionAndPreservesAttemptAnswersDeadlineAndSafeEvents() throws Exception {
        Fixture fixture = fixture();
        Browser first = new Browser(port, json);
        Browser second = new Browser(port, json);
        Browser instructorA = new Browser(port, json);
        Browser instructorB = new Browser(port, json);
        try {
            assertThat(first.login(fixture.studentAName()).statusCode()).isEqualTo(204);
            Attempt before = attempts.findById(fixture.attemptA()).orElseThrow();
            List<Answer> savedBefore = answers.findByAttempt(fixture.attemptA());
            assertThat(savedBefore).hasSize(1);
            assertThat(first.get(attemptPath(fixture.attemptA())).statusCode()).isEqualTo(200);

            // The rejected login is recorded while the student's attempt is current.
            assertDenied(second.login(fixture.studentAName()), 409, "SESSION_IN_USE");
            assertThat(first.get("/api/me").statusCode()).isEqualTo(200);
            assertThat(instructorA.login(fixture.instructorAName()).statusCode()).isEqualTo(204);
            assertThat(instructorB.login(fixture.instructorBName()).statusCode()).isEqualTo(204);

            String rosterPath = activePath(fixture.examA());
            HttpResponse<String> roster = instructorA.get(rosterPath);
            assertThat(roster.statusCode()).isEqualTo(200);
            JsonNode active = json.readTree(roster.body());
            assertThat(active).hasSize(1);
            assertThat(active.get(0).get("attemptId").asLong()).isEqualTo(fixture.attemptA());
            assertThat(active.get(0).get("studentId").asLong()).isEqualTo(fixture.studentA());
            assertThat(active.get(0).get("deadlineAt").asText()).isEqualTo(before.deadlineAt().toString());
            assertSafe(roster.body(), fixture);

            HttpResponse<String> reset = instructorA.post(resetPath(fixture.attemptA()));
            assertThat(reset.statusCode()).isEqualTo(204);
            assertThat(reset.body()).isEmpty();
            assertDenied(first.get("/api/me"), 401, "SESSION_EXPIRED");
            assertThat(registry.getAllSessions(principal(fixture.studentA()), false)).isEmpty();

            assertThat(second.login(fixture.studentAName()).statusCode()).isEqualTo(204);
            HttpResponse<String> resumed = second.get(attemptPath(fixture.attemptA()));
            assertThat(resumed.statusCode()).isEqualTo(200);
            JsonNode resume = json.readTree(resumed.body());
            assertThat(resume.get("deadlineAt").asText()).isEqualTo(before.deadlineAt().toString());
            assertThat(resume.get("questions").get(0).get("selectedOptionId").asLong())
                    .isEqualTo(fixture.selectedOption());
            assertThat(resume.get("questions").get(0).get("revision").asInt()).isZero();
            assertSafe(resumed.body(), fixture);
            assertThat(attempts.findById(fixture.attemptA()).orElseThrow()).isEqualTo(before);
            assertThat(answers.findByAttempt(fixture.attemptA())).isEqualTo(savedBefore);

            HttpResponse<String> eventResponse = instructorA.get(eventPath(fixture.examA()));
            assertThat(eventResponse.statusCode()).isEqualTo(200);
            JsonNode eventRows = json.readTree(eventResponse.body());
            assertThat(eventRows).hasSize(2);
            assertThat(eventResponse.body()).contains("DUPLICATE_LOGIN", "SESSION_RECOVERY");
            assertSafe(eventResponse.body(), fixture);
            assertThat(eventResponse.body()).doesNotContain("detail", fixture.studentBName());
            assertDenied(instructorB.get(eventPath(fixture.examA())), 404, "NOT_FOUND");
            assertDenied(second.get(eventPath(fixture.examA())), 403, "FORBIDDEN");
            List<SecurityEvent> recorded = events.findByExam(fixture.examA());
            assertThat(recorded).hasSize(1);
            assertThat(recorded.get(0).eventType()).isEqualTo("SESSION_RECOVERY");
            assertThat(recorded.get(0).userId()).isEqualTo(fixture.instructorA());
            assertThat(recorded.get(0).attemptId()).isEqualTo(fixture.attemptA());
            assertThat(recorded.get(0).detail()).doesNotContain(PASSWORD, "JSESSIONID", "csrf", "token");
        } finally {
            first.logoutIfPossible();
            second.logoutIfPossible();
            instructorA.logoutIfPossible();
            instructorB.logoutIfPossible();
            cleanup(fixture);
        }
    }

    @Test
    void recoveryRejectsMissingCsrfWrongRoleForeignOwnerTerminalExpiredAndForeignActiveExam()
            throws Exception {
        Fixture fixture = fixture();
        Browser instructorA = new Browser(port, json);
        Browser instructorB = new Browser(port, json);
        Browser student = new Browser(port, json);
        Browser anonymous = new Browser(port, json);
        try {
            assertThat(instructorA.login(fixture.instructorAName()).statusCode()).isEqualTo(204);
            assertThat(instructorB.login(fixture.instructorBName()).statusCode()).isEqualTo(204);
            assertThat(student.login(fixture.studentAName()).statusCode()).isEqualTo(204);
            String path = resetPath(fixture.attemptA());
            Attempt before = attempts.findById(fixture.attemptA()).orElseThrow();
            List<Answer> savedBefore = answers.findByAttempt(fixture.attemptA());

            assertDenied(instructorA.postWithToken(path, null), 403, "FORBIDDEN");
            assertDenied(instructorA.postWithToken(path, "wrong-token"), 403, "FORBIDDEN");
            assertDenied(anonymous.post(path), 401, "UNAUTHENTICATED");
            assertDenied(student.post(path), 403, "FORBIDDEN");
            assertDenied(instructorB.post(path), 404, "NOT_FOUND");
            assertDenied(instructorB.get(activePath(fixture.examA())), 404, "NOT_FOUND");
            assertDenied(student.get(activePath(fixture.examA())), 403, "FORBIDDEN");
            assertDenied(anonymous.get(activePath(fixture.examA())), 401, "UNAUTHENTICATED");

            Instant now = clock.instant();
            long terminal = attempts.create(new Attempt(0, fixture.examA(), fixture.studentB(),
                    AttemptStatus.SUBMITTED, now.minusSeconds(600), now.plusSeconds(600),
                    now.minusSeconds(300), 0, 3));
            assertDenied(instructorA.post(resetPath(terminal)), 409, "RECOVERY_UNAVAILABLE");
            long expired = attempts.create(new Attempt(0, fixture.examB(), fixture.studentB(),
                    AttemptStatus.IN_PROGRESS, now.minusSeconds(600), now.minusSeconds(300),
                    null, null, null));
            assertDenied(instructorB.post(resetPath(expired)), 409, "RECOVERY_UNAVAILABLE");

            long foreignActive = attempts.create(new Attempt(0, fixture.examB(), fixture.studentA(),
                    AttemptStatus.IN_PROGRESS, now.minusSeconds(30), now.plusSeconds(1800),
                    null, null, null));
            assertDenied(instructorA.post(path), 409, "RECOVERY_CONFLICT");
            assertThat(instructorB.get(activePath(fixture.examB())).body())
                    .contains(Long.toString(foreignActive));
            assertThat(student.get("/api/me").statusCode()).isEqualTo(200);
            assertThat(attempts.findById(fixture.attemptA()).orElseThrow()).isEqualTo(before);
            assertThat(answers.findByAttempt(fixture.attemptA())).isEqualTo(savedBefore);
            assertThat(events.findByExam(fixture.examA())).isEmpty();
            assertThat(events.findByExam(fixture.examB())).isEmpty();
        } finally {
            instructorA.logoutIfPossible();
            instructorB.logoutIfPossible();
            student.logoutIfPossible();
            cleanup(fixture);
        }
    }

    @Test
    void concurrentRecoveryAndLoginKeepAtMostOneStudentSession() throws Exception {
        Fixture fixture = fixture();
        Browser first = new Browser(port, json);
        Browser second = new Browser(port, json);
        Browser third = new Browser(port, json);
        Browser instructor = new Browser(port, json);
        var workers = Executors.newFixedThreadPool(2);
        try {
            assertThat(first.login(fixture.studentAName()).statusCode()).isEqualTo(204);
            assertThat(instructor.login(fixture.instructorAName()).statusCode()).isEqualTo(204);
            String loginToken = second.csrf();
            String recoveryToken = instructor.csrf();
            CountDownLatch start = new CountDownLatch(1);
            var login = workers.submit(() -> {
                start.await();
                return second.loginWithToken(fixture.studentAName(), loginToken);
            });
            var reset = workers.submit(() -> {
                start.await();
                return instructor.postWithToken(resetPath(fixture.attemptA()), recoveryToken);
            });
            start.countDown();
            HttpResponse<String> loginResponse = login.get(15, TimeUnit.SECONDS);
            HttpResponse<String> resetResponse = reset.get(15, TimeUnit.SECONDS);
            assertThat(resetResponse.statusCode()).isEqualTo(204);
            assertThat(List.of(204, 409)).contains(loginResponse.statusCode());
            assertDenied(first.get("/api/me"), 401, "SESSION_EXPIRED");
            assertThat(registry.getAllSessions(principal(fixture.studentA()), false).size())
                    .isLessThanOrEqualTo(1);
            if (loginResponse.statusCode() == 409) {
                assertThat(second.login(fixture.studentAName()).statusCode()).isEqualTo(204);
            }
            assertThat(second.get("/api/me").statusCode()).isEqualTo(200);
            assertThat(registry.getAllSessions(principal(fixture.studentA()), false)).hasSize(1);
            assertDenied(third.login(fixture.studentAName()), 409, "SESSION_IN_USE");
            assertThat(second.get(attemptPath(fixture.attemptA())).statusCode()).isEqualTo(200);
            assertThat(events.findByExam(fixture.examA())).filteredOn(
                    event -> event.eventType().equals("SESSION_RECOVERY")).hasSize(1);
        } finally {
            workers.shutdownNow();
            first.logoutIfPossible();
            second.logoutIfPossible();
            third.logoutIfPossible();
            instructor.logoutIfPossible();
            cleanup(fixture);
        }
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String instructorAName = "recover_ia_" + suffix;
        String instructorBName = "recover_ib_" + suffix;
        String studentAName = "recover_sa_" + suffix;
        String studentBName = "recover_sb_" + suffix;
        long instructorA = provisioning.provision(instructorAName, "Recovery Instructor A", Role.INSTRUCTOR, PASSWORD);
        long instructorB = provisioning.provision(instructorBName, "Recovery Instructor B", Role.INSTRUCTOR, PASSWORD);
        long studentA = provisioning.provision(studentAName, "Recovery Student A", Role.STUDENT, PASSWORD);
        long studentB = provisioning.provision(studentBName, "Recovery Student B", Role.STUDENT, PASSWORD);
        Instant now = clock.instant();
        long examA = exams.create(new Exam(0, instructorA, "Recovery exam A", "", 90,
                now.minusSeconds(3600), now.plusSeconds(7200), ExamStatus.PUBLISHED,
                now.minusSeconds(3600)));
        long examB = exams.create(new Exam(0, instructorB, "Recovery exam B", "", 90,
                now.minusSeconds(3600), now.plusSeconds(7200), ExamStatus.PUBLISHED,
                now.minusSeconds(3600)));
        long question = questions.create(new Question(0, examA, "Recovery question", 3, 0));
        options.create(new QuestionOption(0, question, "Correct", true, 0));
        long selected = options.create(new QuestionOption(0, question, "Selected", false, 1));
        long otherQuestion = questions.create(new Question(0, examB, "Other recovery question", 2, 0));
        options.create(new QuestionOption(0, otherQuestion, "Yes", true, 0));
        options.create(new QuestionOption(0, otherQuestion, "No", false, 1));
        long attemptA = attempts.create(new Attempt(0, examA, studentA, AttemptStatus.IN_PROGRESS,
                now.minusSeconds(60), now.plusSeconds(3600), null, null, null));
        answers.create(new Answer(attemptA, examA, question, selected, 0, now.minusSeconds(10)));
        return new Fixture(instructorA, instructorB, studentA, studentB, instructorAName,
                instructorBName, studentAName, studentBName, examA, examB, attemptA, selected);
    }

    private void cleanup(Fixture fixture) {
        jdbc.update("DELETE FROM security_events WHERE user_id IN (?, ?, ?, ?)",
                fixture.instructorA(), fixture.instructorB(), fixture.studentA(), fixture.studentB());
        jdbc.update("DELETE FROM attempts WHERE exam_id IN (?, ?)", fixture.examA(), fixture.examB());
        jdbc.update("DELETE FROM exams WHERE id IN (?, ?)", fixture.examA(), fixture.examB());
        jdbc.update("DELETE FROM users WHERE id IN (?, ?, ?, ?)",
                fixture.instructorA(), fixture.instructorB(), fixture.studentA(), fixture.studentB());
    }

    private ExamPrincipal principal(long id) {
        return ExamPrincipal.from(users.findById(id).orElseThrow());
    }

    private void assertSafe(String body, Fixture fixture) {
        assertThat(body).doesNotContain(PASSWORD, "passwordHash", "JSESSIONID", "_csrf", "correct");
        for (long id : List.of(fixture.instructorA(), fixture.instructorB(),
                fixture.studentA(), fixture.studentB())) {
            assertThat(body).doesNotContain(users.findById(id).orElseThrow().passwordHash());
        }
    }

    private void assertDenied(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(json.readTree(response.body()).get("code").asText()).isEqualTo(code);
    }

    private static String attemptPath(long id) { return "/api/attempts/" + id; }
    private static String resetPath(long id) { return attemptPath(id) + "/session-reset"; }
    private static String activePath(long id) { return "/api/instructor/exams/" + id + "/active-attempts"; }
    private static String eventPath(long id) { return "/api/instructor/exams/" + id + "/events"; }

    private record Fixture(long instructorA, long instructorB, long studentA, long studentB,
                           String instructorAName, String instructorBName,
                           String studentAName, String studentBName,
                           long examA, long examB, long attemptA, long selectedOption) {
    }

    private static final class Browser {
        private final URI origin;
        private final ObjectMapper json;
        private final HttpClient http;

        private Browser(int port, ObjectMapper json) {
            this.origin = URI.create("http://127.0.0.1:" + port);
            this.json = json;
            this.http = HttpClient.newBuilder()
                    .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        }

        private String csrf() throws Exception {
            HttpResponse<String> response = get("/api/csrf");
            assertThat(response.statusCode()).isEqualTo(200);
            return json.readTree(response.body()).get("token").asText();
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

        private HttpResponse<String> post(String path) throws Exception {
            return postWithToken(path, csrf());
        }

        private HttpResponse<String> postWithToken(String path, String token) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(origin.resolve(path));
            if (token != null) request.header("X-CSRF-TOKEN", token);
            return send(request.POST(HttpRequest.BodyPublishers.noBody()).build());
        }

        private HttpResponse<String> get(String path) throws Exception {
            return send(HttpRequest.newBuilder(origin.resolve(path)).GET().build());
        }

        private void logoutIfPossible() {
            try { post("/logout"); } catch (Exception ignored) { }
        }

        private HttpResponse<String> send(HttpRequest request) throws Exception {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }
}
