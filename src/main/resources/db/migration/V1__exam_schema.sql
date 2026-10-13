CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(80) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    password_hash VARCHAR(120) NOT NULL,
    role VARCHAR(20) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT ck_users_role CHECK (role IN ('STUDENT', 'INSTRUCTOR')),
    CONSTRAINT ck_users_enabled CHECK (enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE exams (
    id BIGINT NOT NULL AUTO_INCREMENT,
    instructor_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    duration_minutes INT NOT NULL,
    opens_at DATETIME(6) NOT NULL,
    closes_at DATETIME(6) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    published_at DATETIME(6),
    PRIMARY KEY (id),
    INDEX ix_exams_instructor_status (instructor_id, status),
    INDEX ix_exams_availability (status, opens_at, closes_at),
    CONSTRAINT fk_exams_instructor FOREIGN KEY (instructor_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_exams_duration CHECK (duration_minutes > 0),
    CONSTRAINT ck_exams_window CHECK (closes_at > opens_at),
    CONSTRAINT ck_exams_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT ck_exams_publication CHECK (
        (status = 'DRAFT' AND published_at IS NULL)
        OR (status = 'PUBLISHED' AND published_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE questions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    exam_id BIGINT NOT NULL,
    question_text TEXT NOT NULL,
    marks INT NOT NULL,
    display_order INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_questions_id_exam UNIQUE (id, exam_id),
    CONSTRAINT uq_questions_order UNIQUE (exam_id, display_order),
    CONSTRAINT fk_questions_exam FOREIGN KEY (exam_id) REFERENCES exams(id) ON DELETE CASCADE,
    CONSTRAINT ck_questions_marks CHECK (marks > 0),
    CONSTRAINT ck_questions_order CHECK (display_order >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE question_options (
    id BIGINT NOT NULL AUTO_INCREMENT,
    question_id BIGINT NOT NULL,
    option_text TEXT NOT NULL,
    is_correct BOOLEAN NOT NULL DEFAULT FALSE,
    display_order INT NOT NULL,
    correct_slot TINYINT GENERATED ALWAYS AS (CASE WHEN is_correct = 1 THEN 1 ELSE NULL END) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uq_options_id_question UNIQUE (id, question_id),
    CONSTRAINT uq_options_order UNIQUE (question_id, display_order),
    CONSTRAINT uq_options_one_correct UNIQUE (question_id, correct_slot),
    CONSTRAINT fk_options_question FOREIGN KEY (question_id) REFERENCES questions(id) ON DELETE CASCADE,
    CONSTRAINT ck_options_correct CHECK (is_correct IN (0, 1)),
    CONSTRAINT ck_options_order CHECK (display_order >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attempts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    exam_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    started_at DATETIME(6) NOT NULL,
    deadline_at DATETIME(6) NOT NULL,
    finalized_at DATETIME(6),
    score INT,
    total_marks INT,
    PRIMARY KEY (id),
    CONSTRAINT uq_attempts_exam_student UNIQUE (exam_id, student_id),
    CONSTRAINT uq_attempts_id_exam UNIQUE (id, exam_id),
    INDEX ix_attempts_expiry (status, deadline_at, id),
    INDEX ix_attempts_student_status (student_id, status),
    CONSTRAINT fk_attempts_exam FOREIGN KEY (exam_id) REFERENCES exams(id) ON DELETE RESTRICT,
    CONSTRAINT fk_attempts_student FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_attempts_status CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'TIMED_OUT')),
    CONSTRAINT ck_attempts_deadline CHECK (deadline_at > started_at),
    CONSTRAINT ck_attempts_terminal CHECK (
        (status = 'IN_PROGRESS' AND finalized_at IS NULL AND score IS NULL AND total_marks IS NULL)
        OR (status IN ('SUBMITTED', 'TIMED_OUT') AND finalized_at IS NOT NULL
            AND score IS NOT NULL AND total_marks IS NOT NULL)
    ),
    CONSTRAINT ck_attempts_marks CHECK (score IS NULL OR (score >= 0 AND total_marks >= score)),
    CONSTRAINT ck_attempts_finalized_time CHECK (finalized_at IS NULL OR finalized_at >= started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE answers (
    attempt_id BIGINT NOT NULL,
    exam_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    selected_option_id BIGINT,
    revision INT NOT NULL,
    saved_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attempt_id, question_id),
    INDEX ix_answers_question_exam (question_id, exam_id),
    INDEX ix_answers_option_question (selected_option_id, question_id),
    CONSTRAINT fk_answers_attempt_exam FOREIGN KEY (attempt_id, exam_id)
        REFERENCES attempts(id, exam_id) ON DELETE CASCADE,
    CONSTRAINT fk_answers_question_exam FOREIGN KEY (question_id, exam_id)
        REFERENCES questions(id, exam_id) ON DELETE RESTRICT,
    CONSTRAINT fk_answers_option_question FOREIGN KEY (selected_option_id, question_id)
        REFERENCES question_options(id, question_id) ON DELETE RESTRICT,
    CONSTRAINT ck_answers_revision CHECK (revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE answer_revisions (
    attempt_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    revision INT NOT NULL,
    selected_option_id BIGINT,
    saved_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attempt_id, question_id, revision),
    INDEX ix_answer_revisions_option_question (selected_option_id, question_id),
    CONSTRAINT fk_answer_revisions_answer FOREIGN KEY (attempt_id, question_id)
        REFERENCES answers(attempt_id, question_id) ON DELETE CASCADE,
    CONSTRAINT fk_answer_revisions_option FOREIGN KEY (selected_option_id, question_id)
        REFERENCES question_options(id, question_id) ON DELETE RESTRICT,
    CONSTRAINT ck_answer_revisions_positive CHECK (revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE security_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    exam_id BIGINT,
    attempt_id BIGINT,
    event_type VARCHAR(64) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    detail VARCHAR(400) NOT NULL,
    PRIMARY KEY (id),
    INDEX ix_security_events_user_time (user_id, occurred_at),
    INDEX ix_security_events_exam_time (exam_id, occurred_at),
    INDEX ix_security_events_attempt (attempt_id),
    CONSTRAINT fk_security_events_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_security_events_exam FOREIGN KEY (exam_id) REFERENCES exams(id) ON DELETE RESTRICT,
    CONSTRAINT fk_security_events_attempt FOREIGN KEY (attempt_id) REFERENCES attempts(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
