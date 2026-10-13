# HTTP API

All API responses are JSON except successful login/logout and session reset (`204 No Content`). The UI uses a server session cookie. Obtain `GET /api/csrf` before login and send the returned token under its `headerName` or `parameterName` on every POST, PUT and DELETE. Obtain a fresh token after login or logout. `POST /login` accepts form fields `username` and `password`; `POST /logout` ends the session. `GET /api/me` returns the signed-in user's ID, username, display name and role.

| Method and path | Role | Purpose |
|---|---|---|
| `GET /api/ready` | public | Database readiness (`200` only when MySQL responds) |
| `GET /api/csrf` | public | Current session's CSRF header name, parameter name and token |
| `GET /api/me` | signed in | Current account identity and role |
| `GET /api/instructor/exams` | instructor | Own draft and published paper list |
| `GET /api/instructor/exams/{examId}` | owning instructor | Paper with questions, options and answer-key flags |
| `POST /api/exams` | instructor | Create draft (`201`, `Location`) |
| `PUT /api/exams/{examId}` / `DELETE /api/exams/{examId}` | owning instructor | Edit or delete own draft |
| `POST /api/exams/{examId}/questions` | owning instructor | Add draft MCQ (`201`, `Location`) |
| `PUT /api/questions/{questionId}` / `DELETE /api/questions/{questionId}` | owning instructor | Edit or delete draft MCQ |
| `POST /api/exams/{examId}/publish` | owning instructor | Validate and freeze paper |
| `GET /api/exams` / `GET /api/exams/{examId}` | student | Published catalog and timing metadata, with no questions/keys |
| `POST /api/exams/{examId}/attempts` | student | Start or resume one attempt within the opening/closing policy |
| `GET /api/attempts` / `GET /api/attempts/{attemptId}` | owning student | Own attempts; detail includes questions, saved selections/revisions, server time and original deadline but no keys |
| `PUT /api/attempts/{attemptId}/answers/{questionId}` | owning student | Save or clear one choice with an expected revision |
| `POST /api/attempts/{attemptId}/submit` | owning student | Finalize once and return aggregate result |
| `GET /api/attempts/{attemptId}/result` | owning student | Persisted aggregate grade after finalization |
| `GET /api/instructor/exams/{examId}/results` | owning instructor | Terminal roster with each student's aggregate result |
| `GET /api/instructor/exams/{examId}/events` | owning instructor | Scoped, sanitized security events |
| `GET /api/instructor/exams/{examId}/active-attempts` | owning instructor | Current attempt roster for supervised recovery |
| `POST /api/attempts/{attemptId}/session-reset` | owning instructor | End lost student session, retain answers/deadline (`204`) |

### Main request and response contracts

`ExamRequest` accepts `title` (1–200), `description` (up to 5000), `durationMinutes` (1–10080), and ISO-8601 `opensAt`/`closesAt`. A `QuestionRequest` has `text` (1–5000), `marks` (1–1000), `displayOrder` (nonnegative) and 2–6 `options`; each option has `text`, `correct` and nonnegative `displayOrder`. Publication requires a nonempty valid paper and exactly one correct option per question. Published paper content, keys, marks and timing cannot be changed.

`PUT .../answers/{questionId}` accepts `{"selectedOptionId":123,"expectedRevision":0}`; set `selectedOptionId` to `null` to clear. A successful response contains `questionId`, `selectedOptionId`, new `revision` and `savedAt`. On a stale revision the API returns `409` with `code: "STALE_REVISION"` and the current saved answer. The UI only marks an answer saved after a successful response.

Attempt detail includes `id`, `examId`, `status`, `startedAt`, `deadlineAt`, `finalizedAt`, `score`, `totalMarks`, `serverNow`, and ordered `questions`; each question has choices, `selectedOptionId` and `revision`. Student options have no `correct` field. After submit or timeout, a result has `attemptId`, `examId`, terminal `status`, `score`, `totalMarks`, `percentage`, and timestamps. Score is calculated by Java from stored answers and the server-held key. Client-supplied scores are ignored.

Validation errors use a fixed `{"code":"BAD_REQUEST","message":"Check the submitted data."}` shape. Authentication returns `401`; role/CSRF denial returns `403`; duplicate student login returns `409 SESSION_IN_USE`; throttled login returns `429 LOGIN_THROTTLED` with `Retry-After`. Other policy conflicts (stale revision, unavailable exam, terminal attempt) use fixed codes/messages and do not expose database details. Foreign record IDs are hidden or denied by ownership checks.

Server time and transaction state control opening, deadline, saving and finalization. Saves and submit/timeout share a row lock on the attempt, so a late or terminal write cannot alter the result. Refer to [architecture](../ARCHITECTURE.md) and [database contract](DATABASE.md) for invariants.
