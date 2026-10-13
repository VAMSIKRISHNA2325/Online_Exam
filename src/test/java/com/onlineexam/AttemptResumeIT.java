package com.onlineexam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineexam.domain.Answer;
import com.onlineexam.domain.AnswerRevision;
import com.onlineexam.domain.Attempt;
import com.onlineexam.domain.AttemptStatus;
import com.onlineexam.domain.Exam;
import com.onlineexam.domain.ExamStatus;
import com.onlineexam.domain.Question;
import com.onlineexam.domain.QuestionOption;
import com.onlineexam.domain.Role;
import com.onlineexam.domain.User;
import com.onlineexam.dto.StudentAttemptView;
import com.onlineexam.repository.AnswerRepository;
import com.onlineexam.repository.AttemptRepository;
import com.onlineexam.repository.ExamRepository;
import com.onlineexam.repository.QuestionOptionRepository;
import com.onlineexam.repository.QuestionRepository;
import com.onlineexam.repository.UserRepository;
import com.onlineexam.security.ExamPrincipal;
import com.onlineexam.service.AnswerService;
import com.onlineexam.service.AttemptService;
import com.onlineexam.dto.AnswerSaveRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
class AttemptResumeIT {
    private static final String JDBC_URL = IntegrationDb.url();

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private ExamRepository exams;
    @Autowired private QuestionRepository questions;
    @Autowired private QuestionOptionRepository options;
    @Autowired private AttemptRepository attempts;
    @Autowired private AnswerRepository answers;
    @Autowired private AttemptService attemptService;
    @Autowired private AnswerService answerService;

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> JDBC_URL);
        properties.add("spring.datasource.username", IntegrationDb::username);
        properties.add("spring.datasource.password", IntegrationDb::password);
    }

    @AfterEach
    void clearActor() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @Transactional
    void resumePayloadIsOwnerOnlyAndContainsAuthoritativeTimeAndOnlyOwnAnswers() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        long teacher = user(Role.INSTRUCTOR, now);
        long firstStudent = user(Role.STUDENT, now);
        long secondStudent = user(Role.STUDENT, now);
        long examId = exam(teacher, now);
        long questionId = questions.create(new Question(0, examId, "Pick one", 7, 0));
        long firstOption = options.create(new QuestionOption(0, questionId, "Alpha", true, 0));
        long secondOption = options.create(new QuestionOption(0, questionId, "Beta", false, 1));
        Instant firstDeadline = now.plusSeconds(900);
        Instant secondDeadline = now.plusSeconds(700);
        long firstAttempt = attempt(examId, firstStudent, now, firstDeadline);
        long secondAttempt = attempt(examId, secondStudent, now, secondDeadline);
        answers.create(new Answer(firstAttempt, examId, questionId, firstOption, 2,
                now.plusSeconds(10)));
        answers.appendRevision(new AnswerRevision(firstAttempt, questionId, 1,
                secondOption, now.plusSeconds(5)));
        answers.appendRevision(new AnswerRevision(firstAttempt, questionId, 2,
                firstOption, now.plusSeconds(10)));
        answers.create(new Answer(secondAttempt, examId, questionId, secondOption, 1,
                now.plusSeconds(12)));
        answers.appendRevision(new AnswerRevision(secondAttempt, questionId, 1,
                secondOption, now.plusSeconds(12)));

        Instant before = Instant.now();
        MvcResult response = mvc.perform(get("/api/attempts/{id}", firstAttempt)
                        .with(actor(firstStudent, Role.STUDENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstAttempt))
                .andExpect(jsonPath("$.questions[0].selectedOptionId").value(firstOption))
                .andExpect(jsonPath("$.questions[0].revision").value(2))
                .andReturn();
        Instant after = Instant.now();
        JsonNode payload = json.readTree(response.getResponse().getContentAsString());
        assertThat(Instant.parse(payload.get("startedAt").asText())).isEqualTo(now);
        assertThat(Instant.parse(payload.get("deadlineAt").asText())).isEqualTo(firstDeadline);
        assertThat(Instant.parse(payload.get("serverNow").asText())).isBetween(before, after);
        assertThat(payload.get("questions").get(0).get("options").size()).isEqualTo(2);
        Set<String> fields = new HashSet<>();
        collectFields(payload, fields);
        assertThat(fields).doesNotContain("correct", "isCorrect", "passwordHash", "studentId");
        assertThat(response.getResponse().getContentAsString())
                .doesNotContain("\"correct\":", "\"isCorrect\":", "\"passwordHash\":");

        mvc.perform(get("/api/attempts/{id}", secondAttempt)
                        .with(actor(secondStudent, Role.STUDENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].selectedOptionId").value(secondOption))
                .andExpect(jsonPath("$.questions[0].revision").value(1));
        mvc.perform(get("/api/attempts/{id}", secondAttempt)
                        .with(actor(firstStudent, Role.STUDENT)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/attempts/{id}", firstAttempt)
                        .with(actor(secondStudent, Role.STUDENT)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/attempts/{id}", firstAttempt)
                        .with(actor(teacher, Role.INSTRUCTOR)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/attempts/{id}", firstAttempt))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void freshApplicationContextReadsPersistedAttemptWithoutResettingDeadline() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        long teacher = user(Role.INSTRUCTOR, now);
        long student = user(Role.STUDENT, now);
        long examId = exam(teacher, now);
        long questionId = questions.create(new Question(0, examId, "Persisted question", 5, 0));
        long selected = options.create(new QuestionOption(0, questionId, "A", true, 0));
        options.create(new QuestionOption(0, questionId, "B", false, 1));
        long attemptId = 0;
        try {
            actAs(student);
            StudentAttemptView started = attemptService.start(examId);
            attemptId = started.id();
            StudentAttemptView original = started;
            answerService.save(attemptId, questionId, new AnswerSaveRequest(selected, 0));
            SecurityContextHolder.clearContext();

            Map<String, Object> settings = Map.of(
                    "spring.datasource.url", JDBC_URL,
                    "spring.datasource.username", IntegrationDb.username(),
                    "spring.datasource.password", IntegrationDb.password(),
                    "spring.profiles.active", "default");
            Instant before = Instant.now();
            try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(
                    OnlineExamApplication.class)
                    .web(WebApplicationType.NONE)
                    .initializers(context -> context.getEnvironment().getPropertySources()
                            .addFirst(new MapPropertySource("resumeIntegrationDatabase", settings)))
                    .run()) {
                actAs(student);
                StudentAttemptView resumed = restarted.getBean(AttemptService.class).read(attemptId);
                Instant after = Instant.now();
                assertThat(resumed.id()).isEqualTo(original.id());
                assertThat(resumed.startedAt()).isEqualTo(original.startedAt());
                assertThat(resumed.deadlineAt()).isEqualTo(original.deadlineAt());
                assertThat(resumed.serverNow()).isBetween(before, after);
                assertThat(resumed.questions()).hasSize(1);
                assertThat(resumed.questions().getFirst().selectedOptionId()).isEqualTo(selected);
                assertThat(resumed.questions().getFirst().revision()).isEqualTo(1);
                assertThat(attempts.findById(attemptId)).get()
                        .extracting(Attempt::deadlineAt).isEqualTo(original.deadlineAt());
            }
        } finally {
            SecurityContextHolder.clearContext();
            if (attemptId != 0) jdbc.update("DELETE FROM attempts WHERE id = ?", attemptId);
            jdbc.update("DELETE FROM exams WHERE id = ?", examId);
            jdbc.update("DELETE FROM users WHERE id IN (?, ?)", teacher, student);
        }
    }

    private static void collectFields(JsonNode value, Set<String> fields) {
        if (value.isObject()) {
            value.fieldNames().forEachRemaining(name -> {
                fields.add(name);
                collectFields(value.get(name), fields);
            });
        } else if (value.isArray()) {
            value.forEach(child -> collectFields(child, fields));
        }
    }

    private long attempt(long examId, long studentId, Instant start, Instant deadline) {
        return attempts.create(new Attempt(0, examId, studentId, AttemptStatus.IN_PROGRESS,
                start, deadline, null, null, null));
    }

    private long exam(long teacher, Instant now) {
        return exams.create(new Exam(0, teacher, "Resume fixture", "", 20,
                now.minusSeconds(300), now.plusSeconds(3600), ExamStatus.PUBLISHED,
                now.minusSeconds(360)));
    }

    private long user(Role role, Instant now) {
        String username = "resume_" + UUID.randomUUID().toString().replace("-", "");
        return users.create(new User(0, username, "Resume fixture", "{bcrypt}unused",
                role, true, now.minusSeconds(3600)));
    }

    private static RequestPostProcessor actor(long id, Role role) {
        ExamPrincipal principal = principal(id, role);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private static void actAs(long student) {
        ExamPrincipal principal = principal(student, Role.STUDENT);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static ExamPrincipal principal(long id, Role role) {
        return new ExamPrincipal(id, "resume_fixture_" + id, "Resume fixture",
                "{bcrypt}unused", role, true);
    }
}
