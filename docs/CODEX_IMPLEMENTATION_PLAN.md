# Online Examination System: Codex implementation brief

Version 1.0 | 29 September 2026 | Status: implementation not started

This is the complete build specification and execution runbook for Codex. It extends the supplied Online Examination System presentation and the earlier six-week execution plan. This package contains instructions and initial trackers, not a built application or evidence of passed application tests.

## 1. Mission and fixed technology choices

Build a working, persistent online examination application that follows the presentation's flow: login, exam selection, question display, answer submission, automatic evaluation, result display. Deliver runnable source, real MySQL persistence, a usable interface, verified exam behavior and complete setup documentation.

| Area | Required implementation | Basis |
|---|---|---|
| Application language | Java 21 | Java is specified in the PPT; 21 is the proposed project baseline |
| Frontend | HTML, CSS and vanilla JavaScript, served by the Java application | All three are specified in the PPT |
| Database | MySQL 8.4, InnoDB, UTF-8, UTC timestamps | MySQL is specified; 8.4 is a proposed baseline |
| Java web framework | Spring Boot with Spring MVC | Proposed addition; the PPT names no framework |
| Authentication and authorization | Spring Security, server-side sessions | Proposed addition |
| SQL access | Spring JDBC and MySQL Connector/J | Proposed addition; explicit SQL keeps the project understandable |
| Schema migrations | Flyway Community with MySQL support | Proposed implementation tool |
| Build | Maven Wrapper | Proposed reproducible build tool |
| Verification | JUnit/Spring tests; real MySQL integration tests; Playwright in JavaScript for browser flows | Proposed verification tools |
| Local services | Docker Compose for MySQL; native MySQL fallback when Docker is unavailable | Proposed setup choices |

Resolve compatible stable dependency versions during phase P00, record exact versions, and pin them. Use Spring Boot dependency management instead of independently mixing Spring versions. Playwright/Node are development tools only; the application backend remains Java. SQL, YAML, XML and JSON are configuration/data formats, not replacement application stacks.

Do not replace the stack with React, Next.js, Node/Express, Python, SQLite, PostgreSQL, Firebase or a localStorage-only implementation. Do not add AI APIs, microservices or a queue to satisfy this project. A missing tool is an environment issue to resolve, not permission to change the required stack.

## 2. First-release product contract

These defaults make the brief executable without routine clarification. Record them in `docs/DECISIONS.md`; revise only if the user changes a requirement.

| ID | Required behavior |
|---|---|
| R01 | Student and instructor login/logout; hashed passwords; role and ownership checks on the server |
| R02 | Instructors create, edit and delete their own draft exams and draft questions |
| R03 | Instructors publish valid exams; published questions, answer keys, marks and timing become immutable |
| R04 | Registered students list published exams; start only within the opening/closing window |
| R05 | One persisted attempt per student/exam; repeated starts resume or return the existing outcome |
| R06 | Students answer single-correct-option MCQs; save/clear answers; navigate questions; see save state |
| R07 | Refresh/reconnect resumes the same attempt, saved answers and original server deadline |
| R08 | Submit and timeout finalize exactly once; concurrent requests cannot alter a finalized result |
| R09 | Java calculates marks from stored answers and a server-held answer key; result survives restart |
| R10 | Students see only their own aggregate results; instructors see results for their own exams |
| R11 | One active login session per student; deny the second login and retain the first session |
| R12 | Failed duplicate logins and supervised session recovery are recorded without secrets |
| R13 | Security, persistence, deadlines and failure recovery have executable verification |
| R14 | Clean setup, migrations, demo fixtures, user guide and handover are reproducible |

Scope defaults:

- Two roles: `STUDENT` and `INSTRUCTOR`. No administrator portal is necessary.
- Seed at least two instructors and three students in the explicit demo profile. Document a local provisioning command for additional users; normal application profiles must not install known demo passwords.
- All enabled students are eligible for published exams in their availability window. Enrollment groups are a future feature.
- Each question has 2-6 options, exactly one correct option, and positive integer marks. Require at least one valid question before publication.
- Correct answer earns its question marks. Incorrect or unanswered earns zero. No negative marking or subjective grading.
- Immediate aggregate score, maximum score and percentage after finalization. Do not return answer keys to students.
- Drafts are editable/deletable; published exams remain available for history. Create a new draft when content must change.
- Local execution and a deployable package are required. Public hosting is a separate conditional task, not assumed access or authorization.
- A single application instance is the MVP deployment assumption. Do not claim distributed session control or production-scale capacity.

Optional, non-blocking extensions: visibility-change warnings, exports, richer question banks, public registration, enrollment, external SSO and licensed proctoring. They must not delay the required product or be used to inflate completion percentages.

## 3. Observable execution loop

Use a disciplined engineering loop. Keep the record to actions, decisions, evidence and next steps; do not produce a private reasoning transcript.

1. **Inspect:** read active repository instructions, this brief, current trackers, recent worklog, relevant code and `git status`.
2. **Select:** choose one highest-priority task whose dependencies are verified. Write its expected outcome and smallest meaningful verification. Mark it `IN_PROGRESS`.
3. **Implement:** make a coherent, scoped change. Update tests where behavior requires them. Preserve unrelated user work.
4. **Verify:** execute the relevant commands or browser procedure and inspect outputs, test counts and failures. A command returning zero with all tests skipped is not a pass.
5. **Rectify:** if the check fails, reproduce the error and use section 5. Do not move dependent work past a failed gate.
6. **Record:** link the actual verification record, update task/test/blocker state and append a short worklog entry. Mark `VERIFIED` only when the acceptance criteria passed.
7. **Continue:** take the next ready task. At phase boundaries run the integration gate. Continue until core completion or only genuine external blockers remain.

One worker owns one active implementation task. Parallelize independent tasks when useful. The coordinator owns tracker updates and final integration; workers return changed files, verification commands/results and unresolved issues. Use disjoint files or worktrees. Serialize shared migration, build, security configuration and DTO changes.

Progress updates should say what works, what failed and what happens next. Do not stop after scaffolding, screenshots, a plan, or a promising partial demo.

## 4. Trackers and durable state

The package starts with real task and test definitions, all unexecuted. JSON records are authoritative; Markdown summaries are derived. Never reset existing records when resuming or importing this package.

| File | Responsibility |
|---|---|
| `tracking/tasks.json` | Task IDs, dependencies, requirements, acceptance, owner, status, verification and blockers |
| `tracking/tests.json` | Required acceptance checks and their latest evidence-backed outcome |
| `tracking/blockers.json` | Reproducible problems, cause, repair attempts, next action and closure evidence |
| `tracking/verification.json` | Actual command/browser runs, environment, source state, outcomes and evidence paths |
| `tracking/STATUS.md` | Counts, current phase, completed outcomes and next ready task |
| `tracking/WORKLOG.md` | Append-only concise record of execution cycles and changes |
| `tracking/HANDOFF.md` | State needed to resume without repeating work |
| `docs/DECISIONS.md` | Implementation decisions and why they were made |
| `tracking/RISKS.md` | Anticipated risks, distinct from observed blockers |
| `tracking/STATE_CONTRACT.md` | Allowed states and required record fields |
| `tools/validate_trackers.mjs` | Validates tracker structure, links, dependencies and evidence references |

Task flow: `TODO -> READY -> IN_PROGRESS -> REVIEW -> VERIFIED`. Active work may become `BLOCKED`; after verified resolution it returns to `READY`. `DEFERRED` is allowed only for optional work or explicit user-approved scope changes. A required feature cannot be deferred just to obtain a green status.

If a regression is found, reopen the affected VERIFIED task to IN_PROGRESS or BLOCKED, retain its historical evidence, and mark affected test outcomes STALE or FAIL. Return dependent verification gates to TODO until the repaired dependency is verified; preserve completed code and prior records rather than resetting the project.

Verification/test outcomes: `NOT_RUN`, `PASS`, `FAIL`, `BLOCKED`, `STALE`. Keep previous verification records; point each test to the latest applicable record. A relevant code or environment change makes its prior outcome stale until rerun. A tracker validator checks structure and references; it does not prove that the product works.

Progress = verified core tasks / all core tasks. Show required tests separately and list unresolved blockers. Do not count optional deployment work as completed or skipped core work. Every verified task needs at least one actual verification record; documentation tasks can use a recorded file/readthrough validation. Early P00-P07 tasks use bounded task-specific checks (verification test_ids may be empty) because complete catalog cases can span later APIs. Their related_test_ids show eventual coverage, not an early gate. Run and mark a catalog test PASS only when its entire procedure is executable and passes; never mark partial coverage PASS. required_test_ids are hard gates, and the final release requires all 40 complete checks.

Evidence records include exact command or procedure, timestamp, task IDs, test IDs, environment, expected and actual result, exit code where applicable, source snapshot (commit plus relevant uncommitted diff hash, or a source manifest hash), and repository-relative log/artifact paths. Do not put secrets or session cookies into evidence. Mark old evidence stale by affected scope, not automatically because an unrelated documentation file changed.

## 5. Blockers and rectification

### Required procedure

1. Create a blocker only after observing a real problem. Unknown prerequisites are initially risks or tasks.
2. Record the actual symptom, reproduction steps, affected tasks, evidence, category, severity and next action.
3. Diagnose whether the cause is implementation, configuration, dependency compatibility, missing tools, external access, credentials, requirement ambiguity or a defective test.
4. Make the smallest evidence-supported change, rerun the original failing check, then verify the adjacent affected workflow.
5. Close as `RESOLVED` only after both checks succeed; link their verification IDs. `MITIGATED` records reduced impact but still blocks a required incomplete feature.

Allow up to **three distinct repair hypotheses for the same unchanged blocker**. Identical retries without changed conditions do not count as progress. After that, record what was tried, move to an independent ready task, and identify the exact external action needed. A new concrete finding may justify another attempt; record the new evidence rather than silently resetting the counter. This bound is a diagnostic checkpoint, not permission to abandon solvable work.

Severity: `S0` data loss/security breach; `S1` required workflow or verification blocked; `S2` degraded noncritical behavior; `S3` cosmetic. Never complete the core release with an unresolved S0/S1 issue affecting required scope.

| Symptom | Check first | Permitted rectification | Closure evidence |
|---|---|---|---|
| Java/Maven missing | Installed executables and JDK compatibility | Install through permitted means or use the documented available toolchain | Version check and a successful build |
| Maven downloads unavailable | Network/proxy error and cached artifacts | Correct authorized configuration; continue independent work; record access blocker | Dependencies resolve and build executes |
| MySQL refused/denied | Process, port, database name, user grants, redacted connection config | Repair the isolated development DB or connect to a user-provided MySQL instance | Real connection and repository integration check |
| Docker unavailable | Daemon/socket access and local MySQL availability | Use native isolated MySQL test profile; retain real MySQL tests | Same database tests run against MySQL |
| Flyway migration fails | Original SQL error, history and module compatibility | Correct a never-applied local migration; add a forward migration after application | Fresh install and upgrade path both pass |
| CSRF/403 login failure | Token handling, cookie session, role and endpoint mapping | Repair the documented request flow | Authorized request passes; missing/invalid token still fails |
| Refresh loses answers | API response, database write and attempt ID | Fix persistence/resume contract | Browser refresh and DB inspection agree |
| Score or deadline wrong | Stored fixture, UTC clock, answer key, transaction state | Fix service rules and add targeted regression | Known fixture and boundary tests pass |
| Autosave overwrites newer input | Request version and DB update ordering | Use optimistic revision checks and serialized frontend saves | Deliberately reordered requests preserve the latest accepted value |
| Duplicate submissions differ | Locking and finalization state transition | Make all save/finalize paths take the same attempt lock | Race test leaves one immutable result |
| Browser test cannot launch | Browser binary/dependencies and supported environment | Install via permitted means or run on a permitted existing browser environment | Actual browser flow passes; missing browser remains BLOCKED |
| Stale session blocks login | Session lifecycle and legitimate recovery rule | Supervised invalidation of the old session, with an audit entry | Old session fails; resumed attempt preserves time and answers |
| Hosting unavailable | Target, credentials and authorization | Finish local release and hosting instructions; leave deployment conditional | Reachable hosted URL and smoke test only after actual deployment |

Never rectify by disabling authorization/CSRF, accepting client scores, deleting meaningful tests, hardcoding API success, changing the required database or erasing existing data. Do not repeatedly request information already supplied. Ask only when a real missing decision, credential or external capability blocks remaining work.

## 6. Repository and architecture

Use one Maven Java application and one MySQL database. Keep business rules out of controllers and HTML event handlers.

| Path | Purpose |
|---|---|
| `pom.xml`, `mvnw`, `mvnw.cmd` | Pinned reproducible Java build |
| `src/main/java/.../config/` | Security, clock and application configuration |
| `.../controller/`, `.../dto/` | HTTP contract; safe student/instructor-specific payloads |
| `.../service/` | Exam validation, attempt lifecycle, grading and recovery rules |
| `.../domain/` | User, Exam, Question, ExamAttempt, Answer and evaluator abstractions |
| `.../repository/` | Parameterized JDBC queries and transactional persistence |
| `src/main/resources/static/` | HTML pages, shared CSS, JavaScript modules |
| `src/main/resources/db/migration/` | Versioned Flyway schema migrations |
| `src/test/java/` | Unit, security, repository and race tests |
| `e2e/` | JavaScript Playwright tests and locked development dependencies |
| `compose.yaml`, `.env.example` | Local services and documented configuration |
| `docs/`, `tracking/`, `evidence/` | Product documentation, project state and sanitized verification output |

Use validation at request boundaries and again at business-rule boundaries where needed. Define consistent API errors. Inject `Clock` so timing tests are deterministic; production uses a UTC clock. Show OOP through encapsulation, composition, repository abstractions and an `AnswerEvaluator` interface implemented by `McqEvaluator`. Document the role of each class. Add inheritance only if the academic rubric needs it; it does not substitute for authorization.

## 7. Data and transaction contract

Create migrations for these tables. Use appropriate IDs, foreign keys, constraints and indexes. Persist final results in `attempts`; a redundant results table is unnecessary.

| Table | Minimum fields / invariants |
|---|---|
| `users` | id, name, unique username, password_hash, role, enabled |
| `exams` | id, instructor_id, title, description, duration_minutes, opens_at, closes_at, status; positive duration and closing after opening |
| `questions` | id, exam_id, text, marks, display_order; positive integer marks |
| `question_options` | id, question_id, option_text, is_correct, display_order |
| `attempts` | id, exam_id, student_id, status, started_at, deadline_at, finalized_at, score, total_marks; UNIQUE(exam_id, student_id) |
| `answers` | attempt_id, question_id, selected_option_id nullable, revision, saved_at; unique attempt/question pair |
| `security_events` | id, user_id, attempt_id nullable, event_type, occurred_at, minimal redacted detail |

Index attempts by status/deadline for expiry work, exams by instructor/window, and security events by user/time. Validate the selected option belongs to its question and the question belongs to the attempted exam. Publish-time validation requires valid option counts, exactly one correct answer per question, a nonempty paper and an unexpired window.

### Invariants that must always hold

- Obtain the student/instructor identity from authentication, never a request-body owner ID.
- Exam authoring and publication serialize on the exam row; concurrent editing cannot race past publication.
- After publication, exam content, timing and answer keys are immutable. Future revisions require a new draft.
- Repeated/concurrent starts converge on the unique attempt. Handle the uniqueness conflict by returning the existing authorized attempt.
- `deadline_at = min(started_at + duration, closes_at)`. At `now >= deadline_at`, answers are closed.
- In every save and finalization transaction: lock the attempt row, recheck ownership/status/deadline, then act. The lock order is consistent across services.
- Grading and timeout share one finalization service. Sum marks from saved selections and server-only keys, write result and terminal state atomically.
- Terminal states `SUBMITTED` and `TIMED_OUT` are immutable. A repeated submit returns the recorded result.
- A request arriving before expiry but reaching the authoritative check after expiry is late. Explain this rule to students.
- A scheduled task finalizes overdue attempts in bounded batches. Also finalize on reads/mutations after expiry and on startup, so a closed browser or missed scheduler tick cannot extend an exam.
- Database failures roll back the transaction. Never acknowledge a save before commit.

### Autosave concurrency

Use an answer revision field. The client submits `selectedOptionId` (nullable to clear) and `expectedRevision`. Under the attempt lock, accept only the current revision; increment it on a change. On a stale revision return 409 and the current answer/revision. The frontend serializes saves per question; on conflict it reloads current state and lets the latest local selection be deliberately resubmitted if time remains. A retry that matches the already-persisted selection may return that current record, without an extra change. Test multi-tab/reordered requests explicitly. This is a data-integrity control, not an anti-cheating claim.

## 8. HTTP, sessions and interface contract

Serve frontend and APIs from the same origin. Use Spring Security form authentication with server-side sessions, not browser-stored credentials or a custom JWT framework.

| Method / path | Contract |
|---|---|
| `GET /api/csrf` | Obtain the CSRF token needed for login and mutations; safe to request before authentication |
| `POST /login`, `POST /logout` | Form login and CSRF-protected logout; documented success/failure responses |
| `GET /api/me` | Authenticated identity, role and safe display information |
| `GET /api/exams` | Published student catalog; no keys or draft leakage |
| `GET /api/instructor/exams` | Owning instructor's drafts/published exams |
| `POST /api/exams`, `PUT /api/exams/{id}`, `DELETE /api/exams/{id}` | Owner-only creation or draft mutation |
| `POST /api/exams/{id}/questions` | Add a question/options to an owned draft |
| `PUT /api/questions/{id}`, `DELETE /api/questions/{id}` | Edit/delete an owned draft question |
| `POST /api/exams/{id}/publish` | Validate and freeze the paper in one transaction |
| `POST /api/exams/{id}/attempts` | Start/resume the authenticated student's unique attempt |
| `GET /api/attempts/{id}` | Authorized questions/options, saved answers/revisions, serverNow, deadline and state |
| `PUT /api/attempts/{id}/answers/{questionId}` | Save/clear with expected revision; enforce deadline and ownership |
| `POST /api/attempts/{id}/submit` | Atomically finalize once and return persisted result |
| `GET /api/attempts/{id}/result` | Owning student's terminal aggregate result |
| `GET /api/exams/{id}/results`, `GET /api/exams/{id}/events` | Owning instructor's scoped results and events |
| `POST /api/attempts/{id}/session-reset` | Owning instructor's supervised recovery for that exam's student; CSRF protected and audited |

Add an owner-only exam detail endpoint if the editor needs one; document it. Use separate DTOs for authoring and attempting. Student DTOs must never serialize `is_correct` or answer-key fields. Use 400 for malformed input, 401 for unauthenticated requests, 403 for known forbidden operations, 404 for inaccessible object IDs where hiding existence is appropriate, and 409 for state/revision conflicts. Avoid leaking SQL or stack traces. Return a stable error code and readable message.

### Session policy

Configure one active authenticated session per student and reject new concurrent logins. Use the framework's session lifecycle and registry correctly; verify sequential and near-simultaneous login cases. For the single-instance scope, serialize authentication registration per student if necessary to prevent the near-simultaneous race. Keep an existing exam session valid when denying another login.

A browser refresh with its cookie continues the session. Loss of the cookie may require the owning instructor's supervised session reset. That operation verifies exam ownership and student membership through the attempt, invalidates the student's old sessions, records the event, and leaves attempt answers/deadline unchanged. Permit this instructor recovery only for a current IN_PROGRESS attempt. Reject it if the student has another active attempt owned by a different instructor; a historic or terminal attempt never authorizes a global session reset. Take the same per-student lock used for login registration when invalidating sessions. It does not grant time or create another attempt. After an application restart, the user may reauthenticate and resume from MySQL because in-memory sessions were lost. Document this behavior.

Configure and document a 15-minute inactive-session timeout and registry cleanup. During a live exam, authenticated status polling at about 60-second intervals keeps an actively open player connected and synchronizes time. If the cookie is lost before any attempt exists, explain that re-login becomes available after the previous inactive session expires; do not add an unauthenticated reset bypass. Test expiry/cleanup, including this pre-attempt case. Obtain a fresh CSRF token after successful login and after logout before subsequent mutations.

Use password encoding through Spring Security, session fixation protection, HttpOnly cookies, SameSite and CSRF protection. For HTTPS deployment set Secure cookies; document local HTTP configuration separately. Do not create an endpoint for arbitrary student-controlled session reset. Add bounded login throttling and safe input sizes without making real demo users impossible to recover.

### Screens and user feedback

1. Login: role comes from the account; show useful credential/session errors.
2. Instructor dashboard: owned draft/published exams with title, window, duration and status.
3. Exam editor: editable draft metadata, question/option forms, correct-answer selection, marks, publish validation.
4. Student dashboard: upcoming/available/closed exams and own attempt status.
5. Instructions: duration, marking, saving behavior, submission confirmation, late/offline rule and session restriction.
6. Exam player: question navigator, answered/unanswered state, selected option, clear answer, previous/next, server-derived countdown, saved/pending/failed label and submit button.
7. Student result: persisted score, total, percentage, finalized time and submission/timeout status.
8. Instructor results/review: scoped student results and relevant events; supervised recovery control with a clear confirmation of which old session will be invalidated.

Use semantic controls, keyboard access, visible focus, readable contrast and responsive layouts at approximately 360px and desktop widths. Render question text safely as text. The timer display is advisory; recalculate from server timestamps on resume/focus. A pending save does not become "Saved" until the backend acknowledges it. At submission, await pending saves or clearly report their failure; do not silently discard answers. Offline work remains visibly unsaved and can be retried only before the deadline.

The PPT's IP/device and proctoring ideas must be represented honestly. Network changes are not proof of impersonation. Visibility warnings are optional, bypassable signals, not proof of misconduct. Remote-desktop blocking and identity verification require a separate suitable product/integration; do not pretend ordinary JavaScript implements them.

## 9. Implementation phases and exit gates

Tasks in `tracking/tasks.json` contain the detailed work. Phases are gates, not rigid calendar estimates. Advance dependent tasks only after their dependencies verify; independent tasks may proceed while another path is blocked.

| Phase | Build | Exit gate |
|---|---|---|
| P00 | Inspect repository/tools; fix requirements; pin versions; install trackers | Known environment, preserved existing work, documented contract, valid trackers |
| P01 | Maven application, configuration profiles, health check, Compose/native setup | App starts and reports DB readiness; isolated test setup is documented |
| P02 | Migrations, repositories, constraints, demo fixtures | Fresh schema and real MySQL CRUD pass; fixtures are profile-isolated |
| P03 | Login/logout, CSRF, roles, session policy, account provisioning | Valid flow works; cross-role access and duplicate login fail correctly |
| P04 | Instructor APIs and editor, question validation and publish freeze | Instructor creates and publishes a real DB-backed paper |
| P05 | Start/resume, deadline, answer revisions, atomic saves | One attempt survives refresh; late/stale/foreign saves are rejected |
| P06 | Grading, submit, timeout scheduler/startup recovery, result APIs | Known scores, duplicate submission and save/submit races pass |
| P07 | Complete student dashboard/player/results and instructor review | Full browser journey works with visible save/reconnect states |
| P08 | Session recovery, input hardening, event scoping and resilience | Security and recovery acceptance tests pass on real services |
| P09 | Integration, browser, restart and small concurrency pilot | Critical checks pass with evidence; no required check is skipped |
| P10 | Packaging, clean setup, docs and diagrams | Another clean environment can follow the run instructions |
| P11 | Final review, evidence audit, demo and handover | All core tasks verified, required tests pass, unresolved limits disclosed |
| P12 | Conditional public deployment | Only if requested/configured: HTTPS endpoint and deployed smoke checks pass |

At P04-P06 build a small vertical slice through the APIs immediately; do not wait for all UI polish before testing an exam end to end. At every major integration gate check the main branch/worktree, not just isolated worker branches.

## 10. Verification design and commands

Use the prefilled acceptance catalog in `tracking/tests.json`. Tests must check outcomes/invariants, not merely reproduce implementation internals. Write targeted regressions for discovered functional defects. Do not add trivial tests for every label or style edit.

### Required levels

- Unit: scoring cases, publish validation, deadline calculation, state changes using an injected clock.
- Real MySQL integration: migrations, constraints, repositories, transaction rollback, access boundaries, stale revisions and races. Prefer Testcontainers; provide a native isolated MySQL profile if Docker is unavailable. Never substitute H2/SQLite and claim MySQL verification.
- HTTP security: role, ownership, CSRF, missing authentication, duplicate login, safe DTOs and recovery endpoint restrictions.
- Browser: real login -> instructor creation/publication -> student attempt -> save/refresh -> submit -> result; error, keyboard and mobile-width flows.
- Recovery: closed browser expiry, application restart, lost session recovery and failed database writes.
- Pilot: about 10 distinct student accounts in parallel, distinct exams/fixtures where appropriate; verify acknowledged writes and final scores. Record actual environment and timings. This is a demo-scale observation, not a production guarantee.

### Commands Codex must implement and verify

These are target contracts, not claims that they already run. Create the supporting files/profiles/scripts before using them as evidence. On Windows document `mvnw.cmd` equivalents; keep the main runbook understandable without shell-specific environment syntax.

```bash
# Tracker structural validation (included in this package)
node tools/validate_trackers.mjs

# Unit and HTTP checks, after Maven configuration exists
./mvnw test

# Real MySQL integration profile; must fail if infrastructure is unavailable
./mvnw -Pmysql-it verify

# Start the configured local demo; .env/config values must be supplied as documented
docker compose up -d db
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo

# Browser checks, after e2e/package-lock.json and server fixture setup exist
npm --prefix e2e ci
npm --prefix e2e run test:e2e

# Release build and final tracker gate
./mvnw clean verify -Pmysql-it
node tools/validate_trackers.mjs --require-complete
```

Do not assume Maven imports `.env`; wire configuration explicitly and explain how credentials reach the process. Configure Surefire/Failsafe or an equivalent clear split so the integration profile actually executes the intended tests and reports them. `test:e2e` must check server readiness, use an isolated test database/profile and avoid mutating normal demo/user data. Browser installation is an explicit setup step; do not silently skip it. Verify actual artifact names and runtime commands before writing the README.

Record commands with exit codes and output files. For races use separate real transactions/connections and a controlled start barrier; sequential test calls do not prove concurrency safety. For timer tests use controllable clocks/server fixtures, not long brittle sleeps. Keep destructive test fixtures restricted to a positively identified disposable database.

## 11. Decision and change control

Make routine choices autonomously within the contract: package names, CSS spacing, stable compatible versions, table/index names and test structure. Record decisions that affect later work. Existing repository instructions and user changes remain authoritative within the normal instruction hierarchy; this brief does not override them.

Before a schema/contract change, identify affected requirements, tasks, clients and checks. Add a migration if necessary; update the contract and rerun affected verification. Avoid editing a migration already applied to a persistent/shared database. Never run a destructive reset merely because a migration is inconvenient.

Scope additions go into separate optional tasks. A request to switch required language/database, remove security behavior or deploy to a paid external destination requires an actual user decision. Do not create unnecessary approval gates for ordinary local edits and fixes.

## 12. Resume, handoff and final delivery

At the start of every resumed session, read applicable `AGENTS.md`, this brief, `tracking/HANDOFF.md`, `tracking/tasks.json`, open blockers and latest relevant verification. Inspect real files and running services. Reconcile stale tracker claims against the workspace before continuing; do not rebuild working modules from scratch.

Update the handoff after a meaningful milestone and before stopping. Include current task, exact next command/action, modified files, last verified source state, pending tests, active services/ports, migration state and unresolved blockers. If a limit interrupts execution, leave a concrete checkpoint and mark the project incomplete. A prompt cannot guarantee uninterrupted runtime or access that the environment does not provide.

### Core completion checklist

- All core tasks are `VERIFIED` and required acceptance tests are `PASS` with applicable evidence.
- No required test is `NOT_RUN`, `BLOCKED`, `FAIL` or `STALE`; no open blocker prevents required scope.
- Real MySQL persistence and the complete instructor/student browser flow were exercised.
- Save/submit/deadline/session/security invariants are verified; scores survive restart.
- Fresh setup succeeds in an isolated clean environment, with no developer-machine-only assumptions.
- UI has no dead actions, fake success states, runtime console errors or exposed answer keys.
- Runtime configuration, migrations, demo fixtures and provisioning are documented and separated from real data.
- Deliver source, build configuration, `.env.example`, setup/run guide, ER/class diagrams, test evidence index, user guide, demo script and known limitations.
- Provide `docs/FINAL_REPORT.md` with implemented behavior, verification, exact run commands, actual local URL, unresolved limitations and deployment state.

Use clear final states: `CORE_COMPLETE` means the local required product passed; `BLOCKED` means required work is unresolved; `DEPLOYED` additionally requires an actual reachable deployment and smoke evidence. Do not call a release production-ready solely because a college demo works. Stop optional testing when the defined gates are satisfied.

## 13. Launch instruction

After extracting this pack into the intended project directory, use `START_HERE.md`. If using only this Markdown brief, instruct Codex to create the tracker files according to sections 3-5 before implementing. Existing instruction/tracker files must be read and merged, never blindly replaced.

## 14. Source basis

Project requirements are drawn from the supplied presentation and earlier execution plan. The detailed defaults, task breakdown, tracker schemas, database/API contracts and repair policy are project-specific proposals. Official guidance supports keeping instructions, milestone validations and project state in repository files; the particular workflow in this package is authored for this project.

- [OpenAI: long-horizon Codex work](https://developers.openai.com/blog/run-long-horizon-tasks-with-codex)
- [OpenAI: AGENTS.md instructions](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Security session management](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)
- [Spring JDBC reference](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html)
- [MySQL 8.4 reference](https://dev.mysql.com/doc/refman/8.4/en/)
- [Flyway MySQL support](https://documentation.red-gate.com/fd/mysql-277579322.html)
- [Testcontainers MySQL module](https://java.testcontainers.org/modules/databases/mysql/)
- [Playwright installation](https://playwright.dev/docs/intro)
- [MDN Page Visibility API](https://developer.mozilla.org/en-US/docs/Web/API/Page_Visibility_API)
- [Respondus LockDown Browser](https://web.respondus.com/he/lockdownbrowser/)
