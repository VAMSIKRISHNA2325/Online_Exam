# Initial implementation decisions

These decisions began as defaults from the build brief. The implemented and verified state is recorded in `tracking/verification.json` and the current status in `tracking/STATUS.md`.

| ID | Decision | Reason |
|---|---|---|
| D01 | Preserve Java, HTML, CSS, JavaScript and MySQL | Matches technologies explicitly named in the PPT |
| D02 | Use Spring Boot/Security/JDBC | Specific proposed tools for the PPT's unnamed additional frameworks |
| D03 | Two roles; local provisioning; single-correct-option MCQ | Complete manageable first release |
| D04 | Freeze published exam content and timing | Reproducible scoring and stable attempts |
| D05 | Reject second student login; preserve the first | Keeps the earlier execution plan's policy |
| D06 | Supervised owner-instructor session recovery | Recovers lost sessions without resetting time or creating extra attempts |
| D07 | Real MySQL for integration tests | Verifies the actual schema and transaction behavior |
| D08 | Single application instance for MVP | Keeps session-control guarantees within tested scope |
| D09 | Local completion and public deployment are separate | Hosting destination/access has not been provided |

Append future entries with timestamp, affected requirement/task IDs, alternatives considered briefly, decision, reason and impacted verification. Do not silently change hard constraints.

| D10, 2026-09-29 | P00-02, R01–R14: use the existing brief's endpoint and data contracts without adding optional features | The supplied instructions and tracker requirements agree; implement required scope first. |
| D11, 2026-09-29 | P00-03, R14: pin Temurin 21.0.12.1+1, Maven 3.9.16/Wrapper 3.3.4, Spring Boot 3.5.16, MySQL 8.4.11 and Playwright 1.63.0 | Versions are published; Boot manages its framework dependencies. Boot 3.5 support sunset is tracked in RISKS.md and MySQL/Flyway support is qualified by D12. |
| D12, 2026-09-29 | P00-03/P02-01, R13–R14: keep Boot-managed Flyway 11.7.2 for the first migration trial and disclose its MySQL 8.4 warning | The JDBC connection and empty-schema startup pass, but Redgate does not certify MySQL 8.4; real migration and clean install checks are required before compatibility is established. |
| D13, 2026-09-29 | P02-01, R05–R09: store `answers.exam_id` and add an `answer_revisions` table | Composite foreign keys enforce that an answer's attempt and question belong to the same exam and that its option belongs to that question. Revision history persists every accepted change. A generated unique key permits at most one correct option; publication must still require exactly one. |
| D14, 2026-09-29 | P02-03, R01/R14: load five demo users only under the explicit `demo` profile and require an external password | Default startup creates no known accounts. `provision` is a separate non-web operator command; both paths encode passwords before MySQL insertion and reject unintended overwrites. |
| D15, 2026-10-09 | UI-01, R06/R10/R14: replace decorative page layouts with conventional dashboards, system typography, white panels, neutral gray backgrounds, blue actions and a semantic examination table | The user requested less flashy, generalized dashboards. Preserve all exam workflows and verify desktop/mobile, keyboard, offline, safe-text and result behavior on isolated MySQL. |
