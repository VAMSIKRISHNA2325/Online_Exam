# Code simplification

The review covered the Java services, repositories, controllers, security and data shapes, the six frontend scripts, five pages, stylesheet, build configuration and local verification tools. Changes focus on repeated work and code that was harder to read than necessary.

## Changes

- Shortened private names where their purpose stays clear: `index`, `offset`, `viewId`, `answers`, `choices`, `revision` and `next`. Names such as `studentId` and `questionId` retain their meaning.
- Added one ordered option query per exam. Authoring, publication, student paper loading and grading use it instead of querying each question separately. A 30-question paper now uses one option query instead of 30; this is a query-count reduction, not a throughput benchmark.
- The student catalog gets question counts and total marks in one aggregate query. Listing exams uses two queries instead of one plus a question query per exam. Opening one exam calculates totals only for that exam; an empty paper returns zero counts and marks.
- Student attempt lookup uses a map indexed by exam ID. Result buttons are created once with their final label.
- Submission, connection and result controls live in HTML. Removed the JavaScript that generated those fixed elements and shared the repeated unsaved-answer check.
- Expanded compressed HTML and CSS into readable source. CSS tokens remain identical. HTML structure, attributes and meaningful text were compared during formatting.
- Browser checks share database, port, browser and artifact settings. The runner executes their source directly instead of creating rewritten copies. Test ports are configurable; restart and clean setup checks use the configured Java 21 JDK.

## Behavior preserved

Public API fields and routes, database migrations and dependencies are unchanged. Student payloads still use explicit shapes without answer keys. Save and finalize operations retain their shared attempt-row lock, deadline checks, ownership checks and revision validation. Student login still preserves the first session. Separate transaction beans and security guards remain because they enforce these rules.

Live student accounts, the UI/UX exam, answers, results and audit records are kept in `online_exam`. All regression fixtures use isolated schemas. Read-only hashes of all eight application tables are compared before and after the development restart.

## Verification

Executed: 14 Java unit/HTTP checks, 69 real-MySQL integration checks and four Chromium browser flows passed with zero failures, errors or skips. The added MySQL check verifies exam scoping, option ordering and aggregate counts/marks with multiple papers.

Keyboard/offline, safe-text, pending-save feedback, controlled expiry and closed-browser timeout passed. All 16 desktop/mobile views passed without clipping, duplicate IDs or page errors. Actual process restart preserved saved answers and deadlines and finalized an overdue attempt. A fresh source build migrated a new schema with no default accounts; a backup restored into another new schema matched its source data. Four negative runner checks rejected live schemas, invalid ports and an occupied live port before creating fixtures.

The verified package is running on the original local URL and existing ngrok tunnel. Served assets match source bytes, the local instructor login/logout works, and all eight live application tables match their pre-refactor hashes. The UI/UX paper still has 30 questions, four options per question, one correct answer, a 10-minute duration and the October 10, 2026, 2 AM IST closing time. Public static checks sent no credentials; protected anonymous access returned 401.

Evidence uses `evidence/refactor-*`; historical evidence is retained. The initial Windows JAR lock was resolved by stopping only the launcher-owned process before rebuilding. Tracking task `CODE-01` records the final outcome. Build/test commands are in [README](../README.md).
