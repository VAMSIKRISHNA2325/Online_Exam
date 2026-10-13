# First-release requirements and contract

This is the implementation map for [the full brief](../CODEX_IMPLEMENTATION_PLAN.md). The brief remains the detailed source of truth; this file fixes decisions needed to code and test it.

| ID | Implementation boundary |
|---|---|
| R01 | `STUDENT` and `INSTRUCTOR` authenticate with encoded local passwords and server sessions. CSRF protects writes. Role, record owner and current user come from Spring Security. |
| R02 | An instructor may create, change and delete only their own draft exams, questions and options. |
| R03 | Publication validates at least one question, 2–6 options and exactly one correct option per question, positive marks and a future closing time. Published content, keys, marks and timing are immutable. |
| R04 | Enabled students list published exams and availability. A new attempt starts only in the opening window. |
| R05 | A unique `(exam_id, student_id)` constraint and transactional start logic converge repeated/concurrent starts on one original deadline. |
| R06 | A student saves or clears one selected option per question. Every save locks its attempt row, validates option membership, state, deadline and expected revision, then acknowledges only after commit. |
| R07 | Resume reads server-saved answers and the original deadline. Server time controls expiry; client time is display only. |
| R08 | Submit, scheduled expiry, startup expiry and lazy expiry use the same attempt-row lock and finalization path; terminal attempts are immutable. |
| R09 | Java grades persisted answers against server-held keys; correct earns question marks, wrong/blank earns zero. Result is stored in MySQL. |
| R10 | A student sees only their own aggregate result; instructors see only their exams' results and relevant redacted events. |
| R11 | One active student session is allowed. A second login is rejected while the first stays valid; per-student synchronization handles simultaneous registration. |
| R12 | Duplicate-login denial and owner-instructor supervised recovery are audited. Recovery is limited to a current in-progress attempt and keeps its answers and deadline. |
| R13 | Executable Java, real-MySQL, HTTP security and Playwright checks prove behavior and failure paths. No skipped or zero-test release gate is a pass. |
| R14 | Maven Wrapper, pinned dependencies, migrations, demo-only fixtures, provisioning, setup, recovery and run documentation support repeatable local use. |

## API and persistence rules

Serve HTML and JSON on one Spring Boot origin. Follow the endpoint table in section 8 of the brief, using safe student DTOs that omit answer keys. Use 400 for malformed data, 401 for missing authentication, 403 for known forbidden actions, 404 when hiding an inaccessible object, and 409 for state/revision conflicts. Errors contain a stable code and readable message without SQL or stack traces.

Use MySQL 8.4/InnoDB and Flyway. The operational tables are `users`, `exams`, `questions`, `question_options`, `attempts`, `answers`, `answer_revisions` and `security_events`; Flyway has its own history table. UTC timestamps are authoritative. Keep all exam/result/answer/event writes in MySQL. Use parameterized Spring JDBC SQL. An exam's instructor ID is server-derived and immutable. Use one application instance for this release.

## Scope and safety

Every enabled student is eligible for every published exam in its window. There are exactly two roles and single-answer MCQs. Demo accounts exist only with the explicit `demo` profile. Public registration, exports, enrollment groups, external SSO and proctoring integrations are outside core scope. Browser visibility or device signals are advisory and cannot establish identity or block remote desktop access.

No unresolved contradiction was found among the brief, tracker catalog and `AGENTS.md` at P00-02. Public deployment remains conditional on a configured, authorized target.
