package com.onlineexam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineexam.domain.Exam;
import com.onlineexam.domain.ExamStatus;
import com.onlineexam.domain.Question;
import com.onlineexam.domain.QuestionOption;
import com.onlineexam.domain.Role;
import com.onlineexam.repository.ExamRepository;
import com.onlineexam.repository.QuestionOptionRepository;
import com.onlineexam.repository.QuestionRepository;
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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InstructorRoleEnforcementIT {
    private static final String PASSWORD = "RoleSweepPassword-29!";

    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private AccountProvisioningService provisioning;
    @Autowired private ExamRepository exams;
    @Autowired private QuestionRepository questions;
    @Autowired private QuestionOptionRepository options;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", IntegrationDb::url);
        properties.add("spring.datasource.username", IntegrationDb::username);
        properties.add("spring.datasource.password", IntegrationDb::password);
    }

    @Test
    void studentAndAnonymousCannotUseAnyInstructorMutationOrProtectedRead() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        long ownerId = provisioning.provision("role_owner_" + suffix,
                "Role Owner", Role.INSTRUCTOR, PASSWORD);
        String studentName = "role_student_" + suffix;
        long studentId = provisioning.provision(studentName,
                "Role Student", Role.STUDENT, PASSWORD);
        Instant now = Instant.now();
        long examId = exams.create(new Exam(0, ownerId, "Protected fixture", "Unchanged",
                30, now.minusSeconds(3600), now.plusSeconds(3600), ExamStatus.DRAFT, null));
        long questionId = questions.create(new Question(0, examId, "Original question", 3, 0));
        options.create(new QuestionOption(0, questionId, "Correct answer", true, 0));
        options.create(new QuestionOption(0, questionId, "Wrong answer", false, 1));

        Browser student = new Browser(port, json);
        Browser anonymous = new Browser(port, json);
        try {
            assertThat(student.login(studentName).statusCode()).isEqualTo(204);
            assertThat(student.get("/api/me").statusCode()).isEqualTo(200);
            assertThat(anonymous.get("/api/me").statusCode()).isEqualTo(401);

            String examBody = json.writeValueAsString(Map.of(
                    "title", "Unauthorized change", "description", "Changed",
                    "durationMinutes", 45,
                    "opensAt", now.minusSeconds(1800).toString(),
                    "closesAt", now.plusSeconds(7200).toString()));
            String questionBody = json.writeValueAsString(Map.of(
                    "text", "Unauthorized question", "marks", 5, "displayOrder", 1,
                    "options", List.of(
                            Map.of("text", "A", "correct", true, "displayOrder", 0),
                            Map.of("text", "B", "correct", false, "displayOrder", 1))));
            List<Mutation> mutations = List.of(
                    new Mutation("POST", "/api/exams", examBody),
                    new Mutation("PUT", "/api/exams/" + examId, examBody),
                    new Mutation("DELETE", "/api/exams/" + examId, null),
                    new Mutation("POST", "/api/exams/" + examId + "/questions", questionBody),
                    new Mutation("PUT", "/api/questions/" + questionId, questionBody),
                    new Mutation("DELETE", "/api/questions/" + questionId, null),
                    new Mutation("POST", "/api/exams/" + examId + "/publish", null));

            Snapshot baseline = snapshot(ownerId, studentId, examId, questionId);
            for (Mutation mutation : mutations) {
                HttpResponse<String> studentResponse = student.mutate(mutation);
                assertThat(studentResponse.statusCode())
                        .as("student %s %s", mutation.method(), mutation.path()).isEqualTo(403);
                assertThat(json.readTree(studentResponse.body()).get("code").asText())
                        .isEqualTo("FORBIDDEN");
                assertThat(snapshot(ownerId, studentId, examId, questionId))
                        .as("student mutation left MySQL unchanged: %s %s",
                                mutation.method(), mutation.path()).isEqualTo(baseline);

                HttpResponse<String> anonymousResponse = anonymous.mutate(mutation);
                assertThat(anonymousResponse.statusCode())
                        .as("anonymous %s %s", mutation.method(), mutation.path()).isEqualTo(401);
                assertThat(json.readTree(anonymousResponse.body()).get("code").asText())
                        .isEqualTo("UNAUTHENTICATED");
                assertThat(snapshot(ownerId, studentId, examId, questionId))
                        .as("anonymous mutation left MySQL unchanged: %s %s",
                                mutation.method(), mutation.path()).isEqualTo(baseline);
            }

            for (String path : List.of("/api/instructor/exams",
                    "/api/instructor/exams/" + examId)) {
                assertThat(student.get(path).statusCode()).as("student GET %s", path).isEqualTo(403);
                assertThat(anonymous.get(path).statusCode()).as("anonymous GET %s", path).isEqualTo(401);
            }
            assertThat(snapshot(ownerId, studentId, examId, questionId)).isEqualTo(baseline);
        } finally {
            student.logoutIfPossible();
            jdbc.update("DELETE FROM security_events WHERE user_id IN (?, ?)", ownerId, studentId);
            jdbc.update("DELETE FROM exams WHERE instructor_id IN (?, ?)", ownerId, studentId);
            jdbc.update("DELETE FROM users WHERE id IN (?, ?)", ownerId, studentId);
        }
    }

    private Snapshot snapshot(long ownerId, long studentId, long examId, long questionId) {
        return new Snapshot(exams.findById(examId).orElse(null),
                questions.findById(questionId).orElse(null), options.findByQuestion(questionId),
                jdbc.queryForObject("SELECT COUNT(*) FROM exams WHERE instructor_id = ?", Integer.class, ownerId),
                jdbc.queryForObject("SELECT COUNT(*) FROM exams WHERE instructor_id = ?", Integer.class, studentId),
                jdbc.queryForObject("SELECT COUNT(*) FROM questions WHERE exam_id = ?", Integer.class, examId));
    }

    private record Mutation(String method, String path, String body) {
    }

    private record Snapshot(Exam exam, Question question, List<QuestionOption> options,
                            Integer ownerExamCount, Integer studentExamCount, Integer questionCount) {
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
            HttpResponse<String> response = send(HttpRequest.newBuilder(origin.resolve("/api/csrf"))
                    .GET().build());
            assertThat(response.statusCode()).isEqualTo(200);
            return json.readTree(response.body()).get("token").asText();
        }

        private HttpResponse<String> login(String username) throws Exception {
            String form = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
            return send(HttpRequest.newBuilder(origin.resolve("/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-CSRF-TOKEN", csrf())
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build());
        }

        private HttpResponse<String> mutate(Mutation mutation) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(origin.resolve(mutation.path()))
                    .header("X-CSRF-TOKEN", csrf());
            if (mutation.body() != null) request.header("Content-Type", "application/json");
            request.method(mutation.method(), mutation.body() == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mutation.body()));
            return send(request.build());
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
