package com.onlineexam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.onlineexam.domain.User;
import com.onlineexam.repository.AnswerRepository;
import com.onlineexam.repository.AttemptRepository;
import com.onlineexam.repository.ExamRepository;
import com.onlineexam.repository.QuestionOptionRepository;
import com.onlineexam.repository.QuestionRepository;
import com.onlineexam.repository.UserRepository;
import com.onlineexam.security.ExamPrincipal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
class StudentPrivacyIT {
    private static final String JDBC_URL = IntegrationDb.url();

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private Clock clock;
    @Autowired private UserRepository users;
    @Autowired private ExamRepository exams;
    @Autowired private QuestionRepository questions;
    @Autowired private QuestionOptionRepository options;
    @Autowired private AttemptRepository attempts;
    @Autowired private AnswerRepository answers;

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> JDBC_URL);
        properties.add("spring.datasource.username", IntegrationDb::username);
        properties.add("spring.datasource.password", IntegrationDb::password);
    }

    @Test
    @Transactional
    void everyStudentPayloadKeepsKeysHashesAndOtherStudentPrivate() throws Exception {
        Fixture fixture = fixture();
        List<String> payloads = new ArrayList<>();
        payloads.add(body(mvc.perform(get("/api/csrf").with(actor(fixture.studentA())))
                .andExpect(status().isOk()).andReturn()));
        payloads.add(body(mvc.perform(get("/api/me").with(actor(fixture.studentA())))
                .andExpect(status().isOk()).andReturn()));
        payloads.add(body(mvc.perform(get("/api/exams").with(actor(fixture.studentA())))
                .andExpect(status().isOk()).andReturn()));
        payloads.add(body(mvc.perform(get("/api/exams/{id}", fixture.exam())
                        .with(actor(fixture.studentA())))
                .andExpect(status().isOk()).andReturn()));
        payloads.add(body(mvc.perform(post("/api/exams/{id}/attempts", fixture.exam())
                        .with(actor(fixture.studentA())).with(csrf()))
                .andExpect(status().isOk()).andReturn()));
        long attemptA = json.readTree(payloads.getLast()).get("id").asLong();
        payloads.add(body(mvc.perform(get("/api/attempts").with(actor(fixture.studentA())))
                .andExpect(status().isOk()).andReturn()));
        JsonNode mine = json.readTree(payloads.getLast());
        assertThat(mine.size()).isEqualTo(1);
        assertThat(mine.get(0).get("id").asLong()).isEqualTo(attemptA);
        payloads.add(body(mvc.perform(get("/api/attempts/{id}", attemptA)
                        .with(actor(fixture.studentA())))
                .andExpect(status().isOk()).andReturn()));
        payloads.add(body(mvc.perform(put("/api/attempts/{id}/answers/{question}",
                        attemptA, fixture.question()).with(actor(fixture.studentA())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Save(fixture.correct(), 0))))
                .andExpect(status().isOk()).andReturn()));
        payloads.add(body(mvc.perform(get("/api/attempts/{id}", attemptA)
                        .with(actor(fixture.studentA())))
                .andExpect(status().isOk()).andReturn()));
        payloads.add(body(mvc.perform(post("/api/attempts/{id}/submit", attemptA)
                        .with(actor(fixture.studentA())).with(csrf()))
                .andExpect(status().isOk()).andReturn()));
        payloads.add(body(mvc.perform(get("/api/attempts/{id}/result", attemptA)
                        .with(actor(fixture.studentA())))
                .andExpect(status().isOk()).andReturn()));
        payloads.add(body(mvc.perform(get("/api/attempts/{id}", attemptA)
                        .with(actor(fixture.studentA())))
                .andExpect(status().isOk()).andReturn()));
        for (String payload : payloads) {
            JsonNode tree = json.readTree(payload);
            assertNoSecretFields(tree);
            assertThat(payload).doesNotContain(fixture.studentB().username(),
                    fixture.studentB().displayName(), fixture.studentA().passwordHash(),
                    fixture.studentB().passwordHash());
        }
        for (String page : List.of("login.html", "home.html", "student.html", "exam.html")) {
            try (var source = new ClassPathResource("static/" + page).getInputStream()) {
                String html = new String(source.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(html).doesNotContain("passwordHash", "correctOptionId", "is_correct",
                        fixture.studentA().passwordHash(), fixture.studentB().username());
            }
        }
        mvc.perform(get("/api/instructor/exams/{id}/results", fixture.exam())
                        .with(actor(fixture.studentA())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/instructor/exams/{id}/events", fixture.exam())
                        .with(actor(fixture.studentA())))
                .andExpect(status().isForbidden());
    }

    @Test
    @Transactional
    void secondStudentCannotReadSaveSubmitOrGetResultOfFirstStudentsAttempt() throws Exception {
        Fixture fixture = fixture();
        MvcResult started = mvc.perform(post("/api/exams/{id}/attempts", fixture.exam())
                        .with(actor(fixture.studentA())).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        long attemptA = json.readTree(body(started)).get("id").asLong();
        mvc.perform(put("/api/attempts/{id}/answers/{question}", attemptA, fixture.question())
                        .with(actor(fixture.studentA())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Save(fixture.correct(), 0))))
                .andExpect(status().isOk());
        assertPrivate(mvc.perform(get("/api/attempts/{id}", attemptA)
                .with(actor(fixture.studentB()))).andExpect(status().isNotFound()).andReturn());
        assertPrivate(mvc.perform(put("/api/attempts/{id}/answers/{question}",
                        attemptA, fixture.question()).with(actor(fixture.studentB())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Save(fixture.wrong(), 1))))
                .andExpect(status().isNotFound()).andReturn());
        assertPrivate(mvc.perform(post("/api/attempts/{id}/submit", attemptA)
                .with(actor(fixture.studentB())).with(csrf()))
                .andExpect(status().isNotFound()).andReturn());
        assertThat(answers.find(attemptA, fixture.question())).get()
                .extracting(Answer::selectedOptionId).isEqualTo(fixture.correct());
        assertThat(answers.find(attemptA, fixture.question()).orElseThrow().revision()).isEqualTo(1);
        mvc.perform(post("/api/attempts/{id}/submit", attemptA)
                        .with(actor(fixture.studentA())).with(csrf()))
                .andExpect(status().isOk());
        assertPrivate(mvc.perform(get("/api/attempts/{id}/result", attemptA)
                .with(actor(fixture.studentB()))).andExpect(status().isNotFound()).andReturn());
        assertPrivate(mvc.perform(get("/api/attempts/{id}", fixture.attemptB())
                .with(actor(fixture.studentA()))).andExpect(status().isNotFound()).andReturn());
        assertThat(attempts.findById(attemptA).orElseThrow().status())
                .isEqualTo(AttemptStatus.SUBMITTED);
    }

    private void assertPrivate(MvcResult result) throws Exception {
        JsonNode response = json.readTree(body(result));
        assertThat(response.get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(response.toString()).doesNotContain("option", "score", "password", "student");
    }

    private void assertNoSecretFields(JsonNode node) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(field -> {
                assertThat(field).isNotIn("correct", "isCorrect", "correctOptionId",
                        "password", "passwordHash", "password_hash", "studentId", "userId");
                assertNoSecretFields(node.get(field));
            });
        } else if (node.isArray()) {
            node.forEach(this::assertNoSecretFields);
        }
    }

    private Fixture fixture() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String marker = UUID.randomUUID().toString().replace("-", "");
        User teacher = user("privacy_t_" + marker, "Private teacher", Role.INSTRUCTOR, now);
        User studentA = user("privacy_a_" + marker, "Private student A", Role.STUDENT, now);
        User studentB = user("privacy_b_" + marker, "Private student B", Role.STUDENT, now);
        long exam = exams.create(new Exam(0, teacher.id(), "Privacy paper", "", 30,
                now.minusSeconds(3600), now.plusSeconds(3600), ExamStatus.PUBLISHED,
                now.minusSeconds(7200)));
        long question = questions.create(new Question(0, exam, "Choose one", 2, 0));
        long correct = options.create(new QuestionOption(0, question, "Blue", true, 0));
        long wrong = options.create(new QuestionOption(0, question, "Red", false, 1));
        long attemptB = attempts.create(new Attempt(0, exam, studentB.id(),
                AttemptStatus.IN_PROGRESS, now.minusSeconds(60), now.plusSeconds(1740),
                null, null, null));
        return new Fixture(studentA, studentB, exam, question, correct, wrong, attemptB);
    }

    private User user(String username, String name, Role role, Instant now) {
        String hash = "{noop}private-test-" + username;
        long id = users.create(new User(0, username, name, hash, role, true, now));
        return users.findById(id).orElseThrow();
    }

    private static RequestPostProcessor actor(User user) {
        ExamPrincipal principal = ExamPrincipal.from(user);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private record Save(Long selectedOptionId, int expectedRevision) { }

    private record Fixture(User studentA, User studentB, long exam, long question,
                           long correct, long wrong, long attemptB) { }
}
