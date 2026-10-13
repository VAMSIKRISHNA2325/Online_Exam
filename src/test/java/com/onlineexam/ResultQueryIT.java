package com.onlineexam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineexam.domain.Attempt;
import com.onlineexam.domain.AttemptStatus;
import com.onlineexam.domain.Exam;
import com.onlineexam.domain.ExamStatus;
import com.onlineexam.domain.Question;
import com.onlineexam.domain.QuestionOption;
import com.onlineexam.domain.Role;
import com.onlineexam.domain.SecurityEvent;
import com.onlineexam.repository.AttemptRepository;
import com.onlineexam.repository.ExamRepository;
import com.onlineexam.repository.QuestionOptionRepository;
import com.onlineexam.repository.QuestionRepository;
import com.onlineexam.repository.SecurityEventRepository;
import com.onlineexam.repository.UserRepository;
import com.onlineexam.service.AccountProvisioningService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResultQueryIT {
    private static final String PASSWORD = "ResultQueryPassword-29!";
    private static final String PRIVATE_DETAIL = "password=private-token csrf=private-token correct=true";

    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private AccountProvisioningService provisioning;
    @Autowired private ExamRepository exams;
    @Autowired private AttemptRepository attempts;
    @Autowired private QuestionRepository questions;
    @Autowired private QuestionOptionRepository options;
    @Autowired private SecurityEventRepository events;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", IntegrationDb::url);
        properties.add("spring.datasource.username", IntegrationDb::username);
        properties.add("spring.datasource.password", IntegrationDb::password);
    }

    @Test
    void studentGetsOnlyOwnTerminalAggregateAndUnfinishedResultIsUnavailable() throws Exception {
        Fixture fixture = fixture();
        Browser studentA = new Browser(port, json);
        Browser studentB = new Browser(port, json);
        Browser instructorA = new Browser(port, json);
        Browser anonymous = new Browser(port, json);
        try {
            studentA.login(fixture.studentAName());
            studentB.login(fixture.studentBName());
            instructorA.login(fixture.instructorAName());

            String ownPath = "/api/attempts/" + fixture.attemptA() + "/result";
            HttpResponse<String> own = studentA.get(ownPath);
            assertThat(own.statusCode()).isEqualTo(200);
            JsonNode ownResult = json.readTree(own.body());
            assertThat(ownResult.get("attemptId").asLong()).isEqualTo(fixture.attemptA());
            assertThat(ownResult.get("examId").asLong()).isEqualTo(fixture.examA());
            assertThat(ownResult.get("status").asText()).isEqualTo("SUBMITTED");
            assertThat(ownResult.get("score").asInt()).isEqualTo(2);
            assertThat(ownResult.get("totalMarks").asInt()).isEqualTo(3);
            assertThat(ownResult.get("percentage").decimalValue())
                    .isEqualByComparingTo("66.67");
            assertSafe(own.body(), fixture);
            assertThat(own.body()).doesNotContain(fixture.studentBName());

            HttpResponse<String> unfinished = studentA.get(
                    "/api/attempts/" + fixture.unfinishedAttempt() + "/result");
            assertThat(unfinished.statusCode()).isEqualTo(409);
            assertThat(json.readTree(unfinished.body()).get("code").asText())
                    .isEqualTo("RESULT_NOT_READY");
            assertThat(attempts.findById(fixture.unfinishedAttempt()).orElseThrow().status())
                    .isEqualTo(AttemptStatus.IN_PROGRESS);

            assertNotFound(studentB.get(ownPath));
            assertNotFound(studentA.get("/api/attempts/" + fixture.attemptB() + "/result"));
            assertDenied(instructorA.get(ownPath), 403, "FORBIDDEN");
            assertDenied(anonymous.get(ownPath), 401, "UNAUTHENTICATED");
            assertThat(attempts.findById(fixture.attemptA()).orElseThrow().score()).isEqualTo(2);
        } finally {
            studentA.logoutIfPossible();
            studentB.logoutIfPossible();
            instructorA.logoutIfPossible();
            cleanup(fixture);
        }
    }

    @Test
    void instructorReadsOnlyOwnTerminalRosterAndExamEventMetadata() throws Exception {
        Fixture fixture = fixture();
        Browser instructorA = new Browser(port, json);
        Browser instructorB = new Browser(port, json);
        Browser student = new Browser(port, json);
        Browser anonymous = new Browser(port, json);
        try {
            instructorA.login(fixture.instructorAName());
            instructorB.login(fixture.instructorBName());
            student.login(fixture.studentAName());

            String resultPathA = "/api/instructor/exams/" + fixture.examA() + "/results";
            String resultPathB = "/api/instructor/exams/" + fixture.examB() + "/results";
            HttpResponse<String> resultsA = instructorA.get(resultPathA);
            assertThat(resultsA.statusCode()).isEqualTo(200);
            JsonNode rosterA = json.readTree(resultsA.body());
            assertThat(rosterA.isArray()).isTrue();
            assertThat(rosterA).hasSize(1);
            assertThat(rosterA.get(0).get("studentId").asLong()).isEqualTo(fixture.studentA());
            assertThat(rosterA.get(0).get("username").asText()).isEqualTo(fixture.studentAName());
            assertThat(rosterA.get(0).get("result").get("attemptId").asLong())
                    .isEqualTo(fixture.attemptA());
            assertSafe(resultsA.body(), fixture);
            assertThat(resultsA.body()).doesNotContain(fixture.studentBName());

            HttpResponse<String> resultsB = instructorB.get(resultPathB);
            assertThat(resultsB.statusCode()).isEqualTo(200);
            JsonNode rosterB = json.readTree(resultsB.body());
            assertThat(rosterB).hasSize(1);
            assertThat(rosterB.get(0).get("result").get("attemptId").asLong())
                    .isEqualTo(fixture.attemptB());
            assertThat(resultsB.body()).doesNotContain(fixture.studentAName());
            assertSafe(resultsB.body(), fixture);

            assertNotFound(instructorB.get(resultPathA));
            assertNotFound(instructorA.get(resultPathB));
            assertDenied(student.get(resultPathA), 403, "FORBIDDEN");
            assertDenied(anonymous.get(resultPathA), 401, "UNAUTHENTICATED");

            String eventPathA = "/api/instructor/exams/" + fixture.examA() + "/events";
            String eventPathB = "/api/instructor/exams/" + fixture.examB() + "/events";
            HttpResponse<String> eventsA = instructorA.get(eventPathA);
            assertThat(eventsA.statusCode()).isEqualTo(200);
            JsonNode eventListA = json.readTree(eventsA.body());
            assertThat(eventListA).hasSize(2);
            assertThat(eventListA.get(0).get("eventType").asText()).isEqualTo("DUPLICATE_LOGIN");
            assertThat(eventListA.get(0).get("examId").isNull()).isTrue();
            assertThat(eventListA.get(1).get("eventType").asText()).isEqualTo("RECOVERY_CHECK");
            assertThat(eventListA.get(1).get("attemptId").asLong())
                    .isEqualTo(fixture.attemptA());
            assertThat(eventListA.get(0).has("detail")).isFalse();
            assertSafe(eventsA.body(), fixture);
            assertThat(eventsA.body()).doesNotContain("OTHER_EXAM_EVENT", fixture.studentBName());

            HttpResponse<String> eventsB = instructorB.get(eventPathB);
            assertThat(eventsB.statusCode()).isEqualTo(200);
            assertThat(json.readTree(eventsB.body())).hasSize(1);
            assertThat(eventsB.body()).contains("OTHER_EXAM_EVENT")
                    .doesNotContain("RECOVERY_CHECK", "DUPLICATE_LOGIN", fixture.studentAName());
            assertSafe(eventsB.body(), fixture);

            assertNotFound(instructorB.get(eventPathA));
            assertNotFound(instructorA.get(eventPathB));
            assertDenied(student.get(eventPathA), 403, "FORBIDDEN");
            assertDenied(anonymous.get(eventPathA), 401, "UNAUTHENTICATED");
            assertThat(events.findByExam(fixture.examA())).hasSize(1);
            assertThat(events.findByExam(fixture.examA()).get(0).detail())
                    .isEqualTo(PRIVATE_DETAIL);
        } finally {
            instructorA.logoutIfPossible();
            instructorB.logoutIfPossible();
            student.logoutIfPossible();
            cleanup(fixture);
        }
    }

    @Test
    void instructorRosterFinalizesOverdueAttemptThroughSharedRowLockedPath() throws Exception {
        Fixture fixture = fixture();
        Browser instructor = new Browser(port, json);
        Instant now = Instant.now();
        long question = questions.create(new Question(0, fixture.examA(),
                "Overdue result question", 5, 0));
        options.create(new QuestionOption(0, question, "One", true, 0));
        options.create(new QuestionOption(0, question, "Two", false, 1));
        long overdue = attempts.create(new Attempt(0, fixture.examA(), fixture.studentB(),
                AttemptStatus.IN_PROGRESS, now.minusSeconds(600), now.minusSeconds(300),
                null, null, null));
        try {
            instructor.login(fixture.instructorAName());
            HttpResponse<String> response = instructor.get(
                    "/api/instructor/exams/" + fixture.examA() + "/results");
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode roster = json.readTree(response.body());
            assertThat(roster).hasSize(2);
            JsonNode recovered = null;
            for (JsonNode row : roster) {
                if (row.get("result").get("attemptId").asLong() == overdue) recovered = row;
            }
            assertThat(recovered).isNotNull();
            assertThat(recovered.get("result").get("status").asText()).isEqualTo("TIMED_OUT");
            assertThat(recovered.get("result").get("score").asInt()).isZero();
            assertThat(recovered.get("result").get("totalMarks").asInt()).isEqualTo(5);
            assertThat(attempts.findById(overdue).orElseThrow().status())
                    .isEqualTo(AttemptStatus.TIMED_OUT);
            assertSafe(response.body(), fixture);
        } finally {
            instructor.logoutIfPossible();
            cleanup(fixture);
        }
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String instructorAName = "result_ia_" + suffix;
        String instructorBName = "result_ib_" + suffix;
        String studentAName = "result_sa_" + suffix;
        String studentBName = "result_sb_" + suffix;
        long instructorA = provisioning.provision(instructorAName, "Instructor A", Role.INSTRUCTOR, PASSWORD);
        long instructorB = provisioning.provision(instructorBName, "Instructor B", Role.INSTRUCTOR, PASSWORD);
        long studentA = provisioning.provision(studentAName, "Student A", Role.STUDENT, PASSWORD);
        long studentB = provisioning.provision(studentBName, "Student B", Role.STUDENT, PASSWORD);
        Instant now = Instant.now();
        long examA = exams.create(new Exam(0, instructorA, "Exam A", "", 30,
                now.minusSeconds(3600), now.plusSeconds(3600), ExamStatus.PUBLISHED,
                now.minusSeconds(3600)));
        long examB = exams.create(new Exam(0, instructorB, "Exam B", "", 30,
                now.minusSeconds(3600), now.plusSeconds(3600), ExamStatus.PUBLISHED,
                now.minusSeconds(3600)));
        long attemptA = attempts.create(new Attempt(0, examA, studentA, AttemptStatus.SUBMITTED,
                now.minusSeconds(600), now.plusSeconds(600), now.minusSeconds(300), 2, 3));
        long attemptB = attempts.create(new Attempt(0, examB, studentB, AttemptStatus.TIMED_OUT,
                now.minusSeconds(1200), now.minusSeconds(600), now.minusSeconds(600), 1, 3));
        long unfinishedAttempt = attempts.create(new Attempt(0, examB, studentA, AttemptStatus.IN_PROGRESS,
                now.minusSeconds(60), now.plusSeconds(600), null, null, null));
        events.create(new SecurityEvent(0, studentA, examA, attemptA,
                "RECOVERY_CHECK", now.minusSeconds(20), PRIVATE_DETAIL));
        events.create(new SecurityEvent(0, studentB, examB, attemptB,
                "OTHER_EXAM_EVENT", now.minusSeconds(10), PRIVATE_DETAIL));
        events.create(new SecurityEvent(0, studentA, null, null,
                "DUPLICATE_LOGIN", now.minusSeconds(400), PRIVATE_DETAIL));
        return new Fixture(instructorA, instructorB, studentA, studentB, instructorAName,
                instructorBName, studentAName, studentBName, examA, examB,
                attemptA, attemptB, unfinishedAttempt);
    }

    private void cleanup(Fixture fixture) {
        jdbc.update("DELETE FROM security_events WHERE user_id IN (?, ?, ?, ?)",
                fixture.instructorA(), fixture.instructorB(), fixture.studentA(), fixture.studentB());
        jdbc.update("DELETE FROM attempts WHERE exam_id IN (?, ?)", fixture.examA(), fixture.examB());
        jdbc.update("DELETE FROM exams WHERE id IN (?, ?)", fixture.examA(), fixture.examB());
        jdbc.update("DELETE FROM users WHERE id IN (?, ?, ?, ?)",
                fixture.instructorA(), fixture.instructorB(), fixture.studentA(), fixture.studentB());
    }

    private void assertSafe(String body, Fixture fixture) {
        assertThat(body).doesNotContain(PRIVATE_DETAIL, "password=", "csrf=", "correct");
        for (long id : List.of(fixture.instructorA(), fixture.instructorB(),
                fixture.studentA(), fixture.studentB())) {
            assertThat(body).doesNotContain(users.findById(id).orElseThrow().passwordHash());
        }
    }

    private void assertNotFound(HttpResponse<String> response) throws Exception {
        assertDenied(response, 404, "NOT_FOUND");
        assertThat(response.body()).doesNotContain("score", "studentId", "eventType", PRIVATE_DETAIL);
    }

    private void assertDenied(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(json.readTree(response.body()).get("code").asText()).isEqualTo(code);
    }

    private record Fixture(long instructorA, long instructorB, long studentA, long studentB,
                           String instructorAName, String instructorBName,
                           String studentAName, String studentBName,
                           long examA, long examB, long attemptA, long attemptB,
                           long unfinishedAttempt) {
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

        private void login(String username) throws Exception {
            String form = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
            HttpResponse<String> response = send(HttpRequest.newBuilder(origin.resolve("/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-CSRF-TOKEN", csrf())
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build());
            assertThat(response.statusCode()).isEqualTo(204);
        }

        private HttpResponse<String> get(String path) throws Exception {
            return send(HttpRequest.newBuilder(origin.resolve(path)).GET().build());
        }

        private void logoutIfPossible() {
            try {
                send(HttpRequest.newBuilder(origin.resolve("/logout"))
                        .header("X-CSRF-TOKEN", csrf())
                        .POST(HttpRequest.BodyPublishers.noBody()).build());
            } catch (Exception ignored) { }
        }

        private HttpResponse<String> send(HttpRequest request) throws Exception {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }
}
