package com.onlineexam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineexam.controller.ApiException;
import com.onlineexam.domain.Exam;
import com.onlineexam.domain.ExamStatus;
import com.onlineexam.domain.Question;
import com.onlineexam.domain.QuestionOption;
import com.onlineexam.domain.Role;
import com.onlineexam.domain.User;
import com.onlineexam.dto.AuthoringRequests.OptionRequest;
import com.onlineexam.dto.AuthoringRequests.QuestionRequest;
import com.onlineexam.repository.ExamRepository;
import com.onlineexam.repository.QuestionOptionRepository;
import com.onlineexam.repository.QuestionRepository;
import com.onlineexam.repository.UserRepository;
import com.onlineexam.security.ExamPrincipal;
import com.onlineexam.service.ExamAuthoringService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
class ExamAuthoringIT {
    private static final String JDBC_URL = IntegrationDb.url();
    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private ExamRepository exams;
    @Autowired private QuestionRepository questions;
    @Autowired private QuestionOptionRepository options;
    @Autowired private ExamAuthoringService authoring;

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
    void ownerCreatesEditsReadsAndDeletesDraftInMySql() throws Exception {
        long instructor = user(Role.INSTRUCTOR);
        MvcResult created = mvc.perform(post("/api/exams").with(actor(instructor, Role.INSTRUCTOR))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(examInput("First title"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        long examId = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        assertThat(exams.findById(examId)).get().extracting(Exam::instructorId).isEqualTo(instructor);
        mvc.perform(get("/api/instructor/exams").with(actor(instructor, Role.INSTRUCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(examId));

        MvcResult added = mvc.perform(post("/api/exams/{id}/questions", examId)
                        .with(actor(instructor, Role.INSTRUCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(questionInput("Original", 0))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.options[0].correct").value(true))
                .andReturn();
        long questionId = json.readTree(added.getResponse().getContentAsString()).get("id").asLong();
        assertThat(questions.findByExam(examId)).hasSize(1);
        assertThat(options.findByQuestion(questionId)).hasSize(2);

        mvc.perform(put("/api/questions/{id}", questionId).with(actor(instructor, Role.INSTRUCTOR))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(questionInput("Changed", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Changed"));
        assertThat(options.findByQuestion(questionId)).hasSize(2);
        mvc.perform(put("/api/exams/{id}", examId).with(actor(instructor, Role.INSTRUCTOR))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(examInput("Renamed"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renamed"));
        mvc.perform(get("/api/instructor/exams/{id}", examId)
                        .with(actor(instructor, Role.INSTRUCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exam.title").value("Renamed"))
                .andExpect(jsonPath("$.questions[0].text").value("Changed"))
                .andExpect(jsonPath("$.questions[0].options[0].correct").value(true));

        mvc.perform(delete("/api/questions/{id}", questionId)
                        .with(actor(instructor, Role.INSTRUCTOR)).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(questions.findById(questionId)).isEmpty();
        mvc.perform(delete("/api/exams/{id}", examId)
                        .with(actor(instructor, Role.INSTRUCTOR)).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(exams.findById(examId)).isEmpty();
    }

    @Test
    @Transactional
    void studentsAndOtherInstructorsCannotReadOrMutateAnotherDraft() throws Exception {
        long owner = user(Role.INSTRUCTOR);
        long outsider = user(Role.INSTRUCTOR);
        long student = user(Role.STUDENT);
        long examId = exam(owner, ExamStatus.DRAFT);
        long questionId = questions.create(new Question(0, examId, "Private", 2, 0));
        options.create(new QuestionOption(0, questionId, "Yes", true, 0));
        options.create(new QuestionOption(0, questionId, "No", false, 1));

        mvc.perform(get("/api/instructor/exams/{id}", examId)
                        .with(actor(outsider, Role.INSTRUCTOR)))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/exams/{id}", examId).with(actor(outsider, Role.INSTRUCTOR))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(examInput("Theft"))))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/exams/{id}", examId)
                        .with(actor(outsider, Role.INSTRUCTOR)).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/exams/{id}/questions", examId)
                        .with(actor(outsider, Role.INSTRUCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(questionInput("Theft", 1))))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/questions/{id}", questionId).with(actor(outsider, Role.INSTRUCTOR))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(questionInput("Theft", 0))))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/questions/{id}", questionId)
                        .with(actor(outsider, Role.INSTRUCTOR)).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/exams").with(actor(student, Role.STUDENT)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(examInput("Student exam"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/instructor/exams").with(actor(student, Role.STUDENT)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/instructor/exams")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/exams/{id}", examId).with(actor(owner, Role.INSTRUCTOR)))
                .andExpect(status().isForbidden());
        assertThat(exams.findById(examId)).get().extracting(Exam::title).isEqualTo("Fixture exam");
        assertThat(questions.findById(questionId)).get().extracting(Question::text).isEqualTo("Private");
    }

    @Test
    @Transactional
    void publishedPaperRejectsEveryAuthoringMutation() throws Exception {
        long owner = user(Role.INSTRUCTOR);
        long examId = exam(owner, ExamStatus.PUBLISHED);
        long questionId = questions.create(new Question(0, examId, "Frozen", 4, 0));
        long correctId = options.create(new QuestionOption(0, questionId, "Key", true, 0));
        options.create(new QuestionOption(0, questionId, "Distractor", false, 1));

        mvc.perform(put("/api/exams/{id}", examId).with(actor(owner, Role.INSTRUCTOR))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(examInput("Mutated"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXAM_PUBLISHED"));
        mvc.perform(delete("/api/exams/{id}", examId)
                        .with(actor(owner, Role.INSTRUCTOR)).with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/exams/{id}/questions", examId)
                        .with(actor(owner, Role.INSTRUCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(questionInput("Another", 1))))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/questions/{id}", questionId).with(actor(owner, Role.INSTRUCTOR))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(questionInput("Mutated", 0))))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/questions/{id}", questionId)
                        .with(actor(owner, Role.INSTRUCTOR)).with(csrf()))
                .andExpect(status().isConflict());
        assertThat(questions.findById(questionId)).get().extracting(Question::text).isEqualTo("Frozen");
        assertThat(options.findByQuestion(questionId)).extracting(QuestionOption::id).contains(correctId);
    }

    @Test
    @Transactional
    void invalidExamAndQuestionInputsReturnSafeErrorsWithoutWrites() throws Exception {
        long owner = user(Role.INSTRUCTOR);
        long examId = exam(owner, ExamStatus.DRAFT);
        mvc.perform(post("/api/exams").with(actor(owner, Role.INSTRUCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", "Bad window", "durationMinutes", 15,
                                "opensAt", "2026-10-01T00:00:00Z", "closesAt", "2026-09-01T00:00:00Z"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(post("/api/exams/{id}/questions", examId)
                        .with(actor(owner, Role.INSTRUCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("text", "No answer", "marks", 1,
                                "displayOrder", 0, "options", List.of(
                                        Map.of("text", "A", "correct", false, "displayOrder", 0),
                                        Map.of("text", "B", "correct", false, "displayOrder", 1))))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/exams/{id}/questions", examId)
                        .with(actor(owner, Role.INSTRUCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("text", "Duplicate option order", "marks", 1,
                                "displayOrder", 0, "options", List.of(
                                        Map.of("text", "A", "correct", true, "displayOrder", 0),
                                        Map.of("text", "B", "correct", false, "displayOrder", 0))))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/exams/{id}/questions", examId)
                        .with(actor(owner, Role.INSTRUCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("text", "No marks", "marks", 0,
                                "displayOrder", 0, "options", List.of(
                                        Map.of("text", "A", "correct", true, "displayOrder", 0),
                                        Map.of("text", "B", "correct", false, "displayOrder", 1))))))
                .andExpect(status().isBadRequest());
        assertThat(questions.findByExam(examId)).isEmpty();
    }

    @Test
    @Transactional
    void scriptLikePaperAndSqlLikeCredentialsRemainData() throws Exception {
        long owner = user(Role.INSTRUCTOR);
        String scriptText = "<img src=x onerror=window.examInjected=true> ' OR 1=1 --";
        MvcResult created = mvc.perform(post("/api/exams").with(actor(owner, Role.INSTRUCTOR))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", scriptText,
                                "description", "<script>window.examInjected=true</script>",
                                "durationMinutes", 30, "opensAt", "2026-09-29T00:00:00Z",
                                "closesAt", "2026-10-01T00:00:00Z"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value(scriptText))
                .andReturn();
        long examId = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        MvcResult added = mvc.perform(post("/api/exams/{id}/questions", examId)
                        .with(actor(owner, Role.INSTRUCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("text", scriptText, "marks", 1,
                                "displayOrder", 0, "options", List.of(
                                        Map.of("text", scriptText, "correct", true, "displayOrder", 0),
                                        Map.of("text", "Other", "correct", false, "displayOrder", 1))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value(scriptText))
                .andReturn();
        long questionId = json.readTree(added.getResponse().getContentAsString()).get("id").asLong();
        assertThat(exams.findById(examId)).get().extracting(Exam::title).isEqualTo(scriptText);
        assertThat(questions.findById(questionId)).get().extracting(Question::text).isEqualTo(scriptText);
        assertThat(options.findByQuestion(questionId).get(0).text()).isEqualTo(scriptText);
        mvc.perform(post("/login").servletPath("/login").with(csrf()).param("username", "' OR 1=1 --")
                        .param("password", "' OR 1=1 --"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
        mvc.perform(post("/login").servletPath("/login").with(csrf()).param("username", "a".repeat(81))
                        .param("password", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(post("/login").servletPath("/login").with(csrf()).param("username", "fixture")
                        .param("password", "x".repeat(129)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(get("/instructor.html"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Content-Security-Policy"))
                        .contains("script-src 'self'", "object-src 'none'"));
    }

    @Test
    @Transactional
    void numericLimitsRejectOversizedPaperBeforeWriting() throws Exception {
        long owner = user(Role.INSTRUCTOR);
        long examId = exam(owner, ExamStatus.DRAFT);
        mvc.perform(post("/api/exams").with(actor(owner, Role.INSTRUCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", "Too long", "description", "",
                                "durationMinutes", 10081, "opensAt", "2026-09-29T00:00:00Z",
                                "closesAt", "2026-10-01T00:00:00Z"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(post("/api/exams/{id}/questions", examId)
                        .with(actor(owner, Role.INSTRUCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("text", "Too many marks", "marks", 1001,
                                "displayOrder", 0, "options", List.of(
                                        Map.of("text", "A", "correct", true, "displayOrder", 0),
                                        Map.of("text", "B", "correct", false, "displayOrder", 1))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        assertThat(questions.findByExam(examId)).isEmpty();
    }

    @Test
    void failedSecondOptionInsertRestoresOriginalQuestionAndOptions() {
        long owner = user(Role.INSTRUCTOR);
        long examId = exam(owner, ExamStatus.DRAFT);
        long questionId = questions.create(new Question(0, examId, "Original", 3, 0));
        long firstId = options.create(new QuestionOption(0, questionId, "Original key", true, 0));
        long secondId = options.create(new QuestionOption(0, questionId, "Original wrong", false, 1));
        try {
            ExamPrincipal principal = principal(owner, Role.INSTRUCTOR);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
            QuestionRequest tooLargeForMySqlText = new QuestionRequest("Changed", 5, 0,
                    List.of(new OptionRequest("New key", true, 0),
                            new OptionRequest("🚀".repeat(17000), false, 1)));
            assertThatThrownBy(() -> authoring.updateQuestion(questionId, tooLargeForMySqlText))
                    .isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(questions.findById(questionId)).get().extracting(Question::text)
                    .isEqualTo("Original");
            assertThat(options.findByQuestion(questionId)).extracting(QuestionOption::id)
                    .containsExactly(firstId, secondId);
            assertThat(options.findByQuestion(questionId)).extracting(QuestionOption::text)
                    .containsExactly("Original key", "Original wrong");
        } finally {
            jdbc.update("DELETE FROM exams WHERE id = ?", examId);
            jdbc.update("DELETE FROM users WHERE id = ?", owner);
        }
    }

    private static Map<String, Object> examInput(String title) {
        return Map.of("title", title, "description", "Draft description", "durationMinutes", 30,
                "opensAt", "2026-09-29T00:00:00Z", "closesAt", "2026-10-01T00:00:00Z");
    }

    private static Map<String, Object> questionInput(String text, int order) {
        return Map.of("text", text, "marks", 3, "displayOrder", order,
                "options", List.of(Map.of("text", "Correct", "correct", true, "displayOrder", 0),
                        Map.of("text", "Wrong", "correct", false, "displayOrder", 1)));
    }

    private long user(Role role) {
        String username = "authoring_" + UUID.randomUUID().toString().replace("-", "");
        return users.create(new User(0, username, "Authoring fixture", "{bcrypt}unused", role, true, NOW));
    }

    private long exam(long owner, ExamStatus status) {
        return exams.create(new Exam(0, owner, "Fixture exam", "", 30,
                NOW.minusSeconds(3600), NOW.plusSeconds(3600), status,
                status == ExamStatus.PUBLISHED ? NOW : null));
    }

    private static RequestPostProcessor actor(long id, Role role) {
        ExamPrincipal principal = principal(id, role);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private static ExamPrincipal principal(long id, Role role) {
        return new ExamPrincipal(id, "authoring_fixture_" + id, "Authoring fixture",
                "{bcrypt}unused", role, true);
    }
}
