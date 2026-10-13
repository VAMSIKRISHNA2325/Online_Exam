package com.onlineexam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineexam.domain.Exam;
import com.onlineexam.domain.ExamStatus;
import com.onlineexam.domain.Question;
import com.onlineexam.domain.QuestionOption;
import com.onlineexam.domain.Role;
import com.onlineexam.domain.User;
import com.onlineexam.dto.AuthoringRequests.OptionRequest;
import com.onlineexam.dto.AuthoringRequests.QuestionRequest;
import com.onlineexam.dto.AuthoringViews.ExamView;
import com.onlineexam.repository.ExamRepository;
import com.onlineexam.repository.QuestionOptionRepository;
import com.onlineexam.repository.QuestionRepository;
import com.onlineexam.repository.UserRepository;
import com.onlineexam.security.ExamPrincipal;
import com.onlineexam.service.ExamAuthoringService;
import com.onlineexam.service.ExamPublicationService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class ExamPublicationIT {
    private static final String JDBC_URL = IntegrationDb.url();

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private ExamRepository exams;
    @Autowired private QuestionRepository questions;
    @Autowired private QuestionOptionRepository options;
    @Autowired private ExamAuthoringService authoring;
    @Autowired private ExamPublicationService publication;
    @Autowired private PlatformTransactionManager transactionManager;

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
    void invalidDraftRemainsDraftUntilCompleteAndThenFreezes() throws Exception {
        long owner = user(Role.INSTRUCTOR);
        long examId = exam(owner, false);
        invalidPaper(owner, examId);
        long questionId = questions.create(new Question(0, examId, "Question", 3, 0));
        invalidPaper(owner, examId);
        long keyId = options.create(new QuestionOption(0, questionId, "Key", false, 0));
        invalidPaper(owner, examId);
        options.create(new QuestionOption(0, questionId, "Wrong", false, 1));
        invalidPaper(owner, examId);
        for (int order = 2; order < 7; order++) {
            options.create(new QuestionOption(0, questionId, "Extra " + order, false, order));
        }
        invalidPaper(owner, examId);
        for (QuestionOption option : options.findByQuestion(questionId)) {
            if (option.displayOrder() >= 2) options.deleteById(option.id());
        }
        options.update(new QuestionOption(keyId, questionId, "Key", true, 0));

        mvc.perform(post("/api/exams/{id}/publish", examId).with(actor(owner, Role.INSTRUCTOR))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.publishedAt").isNotEmpty());
        assertThat(exams.findById(examId)).get().extracting(Exam::status)
                .isEqualTo(ExamStatus.PUBLISHED);
        mvc.perform(post("/api/exams/{id}/publish", examId).with(actor(owner, Role.INSTRUCTOR))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXAM_PUBLISHED"));
        mvc.perform(put("/api/questions/{id}", questionId).with(actor(owner, Role.INSTRUCTOR))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("text", "Changed", "marks", 9,
                                "displayOrder", 0, "options", List.of(
                                        Map.of("text", "New key", "correct", true, "displayOrder", 0),
                                        Map.of("text", "New wrong", "correct", false, "displayOrder", 1))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXAM_PUBLISHED"));
        assertThat(options.findByQuestion(questionId)).extracting(QuestionOption::id).contains(keyId);
    }

    @Test
    @Transactional
    void outsiderStudentAndAnonymousCannotPublish() throws Exception {
        long owner = user(Role.INSTRUCTOR);
        long outsider = user(Role.INSTRUCTOR);
        long student = user(Role.STUDENT);
        long examId = validExam(owner);
        mvc.perform(post("/api/exams/{id}/publish", examId)
                        .with(actor(outsider, Role.INSTRUCTOR)).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/exams/{id}/publish", examId)
                        .with(actor(student, Role.STUDENT)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/exams/{id}/publish", examId).with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/exams/{id}/publish", examId)
                        .with(actor(owner, Role.INSTRUCTOR)))
                .andExpect(status().isForbidden());
        assertThat(exams.findById(examId)).get().extracting(Exam::status)
                .isEqualTo(ExamStatus.DRAFT);
    }

    @Test
    @Transactional
    void closedWindowCannotBePublished() throws Exception {
        long owner = user(Role.INSTRUCTOR);
        long examId = exam(owner, true);
        long questionId = questions.create(new Question(0, examId, "Question", 1, 0));
        options.create(new QuestionOption(0, questionId, "Yes", true, 0));
        options.create(new QuestionOption(0, questionId, "No", false, 1));
        invalidPaper(owner, examId);
    }

    @Test
    void publicationWaitsForDraftEditAndFreezesTheCommittedPaper() throws Exception {
        long owner = user(Role.INSTRUCTOR);
        long examId = validExam(owner);
        long questionId = questions.findByExam(examId).getFirst().id();
        CountDownLatch editedButUncommitted = new CountDownLatch(1);
        CountDownLatch releaseEditor = new CountDownLatch(1);
        CountDownLatch publicationStarted = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> edit = workers.submit(() -> {
                actAs(owner, Role.INSTRUCTOR);
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                        authoring.updateQuestion(questionId, new QuestionRequest("Committed edit", 5, 0,
                                List.of(new OptionRequest("New key", true, 0),
                                        new OptionRequest("New wrong", false, 1))));
                        editedButUncommitted.countDown();
                        try {
                            if (!releaseEditor.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Editor release timed out");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Editor interrupted", exception);
                        }
                    });
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            assertThat(editedButUncommitted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<ExamView> publish = workers.submit(() -> {
                actAs(owner, Role.INSTRUCTOR);
                try {
                    publicationStarted.countDown();
                    return publication.publish(examId);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            assertThat(publicationStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> publish.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseEditor.countDown();
            edit.get(10, TimeUnit.SECONDS);
            assertThat(publish.get(10, TimeUnit.SECONDS).status()).isEqualTo(ExamStatus.PUBLISHED);
            assertThat(questions.findById(questionId)).get().extracting(Question::text)
                    .isEqualTo("Committed edit");
            assertThat(options.findByQuestion(questionId)).extracting(QuestionOption::text)
                    .containsExactly("New key", "New wrong");
            assertThat(options.findByQuestion(questionId)).filteredOn(QuestionOption::correct)
                    .hasSize(1);
            assertThat(exams.findById(examId)).get().extracting(Exam::status)
                    .isEqualTo(ExamStatus.PUBLISHED);
            mvc.perform(put("/api/questions/{id}", questionId).with(actor(owner, Role.INSTRUCTOR))
                            .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("text", "Too late", "marks", 5,
                                    "displayOrder", 0, "options", List.of(
                                            Map.of("text", "A", "correct", true, "displayOrder", 0),
                                            Map.of("text", "B", "correct", false, "displayOrder", 1))))))
                    .andExpect(status().isConflict());
        } finally {
            releaseEditor.countDown();
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
            jdbc.update("DELETE FROM exams WHERE id = ?", examId);
            jdbc.update("DELETE FROM users WHERE id = ?", owner);
        }
    }

    private void invalidPaper(long owner, long examId) throws Exception {
        mvc.perform(post("/api/exams/{id}/publish", examId)
                        .with(actor(owner, Role.INSTRUCTOR)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PAPER"));
        assertThat(exams.findById(examId)).get().extracting(Exam::status)
                .isEqualTo(ExamStatus.DRAFT);
    }

    private long validExam(long owner) {
        long examId = exam(owner, false);
        long questionId = questions.create(new Question(0, examId, "Original", 3, 0));
        options.create(new QuestionOption(0, questionId, "Original key", true, 0));
        options.create(new QuestionOption(0, questionId, "Original wrong", false, 1));
        return examId;
    }

    private long exam(long owner, boolean closed) {
        Instant now = Instant.now();
        return exams.create(new Exam(0, owner, "Publication fixture", "", 30,
                now.minusSeconds(3600), closed ? now.minusSeconds(60) : now.plusSeconds(3600),
                ExamStatus.DRAFT, null));
    }

    private long user(Role role) {
        String username = "publish_" + UUID.randomUUID().toString().replace("-", "");
        return users.create(new User(0, username, "Publication fixture", "{bcrypt}unused",
                role, true, Instant.now()));
    }

    private static RequestPostProcessor actor(long id, Role role) {
        ExamPrincipal principal = principal(id, role);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private static void actAs(long id, Role role) {
        ExamPrincipal principal = principal(id, role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static ExamPrincipal principal(long id, Role role) {
        return new ExamPrincipal(id, "publish_fixture_" + id, "Publication fixture",
                "{bcrypt}unused", role, true);
    }
}
