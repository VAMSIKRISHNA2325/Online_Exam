# Implementation handoff

## New-device README update, 2026-10-13

- `README.md` now starts with a fresh Windows setup walkthrough and also gives manual Windows and Bash startup steps. It explains which private files and MySQL data are absent from a clone and how the local launcher generates a demo password. The README-specific checks passed; see `evidence/readme-new-device-setup-2026-10-13.md`. No app/database/tunnel state was changed by this edit.
- The broader `tools/check_documentation.py` currently fails because root `START_HERE.md` and other root documents appear to have moved into `docs/` during this task. Those unrelated workspace changes were preserved; reconcile the documentation checker after the moves settle. The prior verified application checks are untouched.

## Code simplification, 2026-10-10

- `CODE-01` is VERIFIED in V0062, with final ledger/audit closure in V0063. Reviewed runtime Java, frontend, configuration and verification tools; simplified 32 source/document files. Private names are shorter, repeated unsaved checks are shared, fixed exam controls are in HTML, and compressed HTML/CSS is readable. See [code review](../docs/CODE_REVIEW.md) and [review diff](../evidence/refactor-review.diff).
- Paper options load once per exam instead of once per question. Student catalog totals use one aggregate query; individual details query only their exam. Student attempts use an exam-ID map. Ownership checks, safe student payloads, session policy, transaction locks, deadlines and server grading remain intact.
- Final package passed 14 Java unit/HTTP, 69 real-MySQL and four Chromium browser tests with no failures/errors/skips. Keyboard/offline, safe text, pending-save feedback, closed-browser expiry, 16 desktop/mobile views, process restart, fresh source/schema and backup/restore passed. Four negative runner checks rejected live schemas and unsafe/occupied ports before creating fixtures. Evidence is under `evidence/refactor-final-*`; earlier evidence is retained.
- The live app is ready at `http://127.0.0.1:8080/`, PID 33752, demo profile, persistent `online_exam`. The existing ngrok URL remains `https://graveness-undrafted-harsh.ngrok-free.dev`. All eight live application tables match their pre-refactor hash; all 15 roster accounts, the UI/UX paper and existing results are preserved. The paper still has 30 questions, four options and one correct answer per question, 10 minutes per attempt, and October 10, 2026, 01:30?02:00 IST availability. That window has now closed.
- B006 is RESOLVED: Windows held the running JAR open. With zero active live attempts, the guarded launcher stopped only its recorded Java process, the original package retry passed, and the verified package restarted. MySQL and its data were retained. Stop the owned app before Maven rebuilds on Windows.
- `E2E_PORT` lets browser, native acceptance, restart and clean setup checks use another port. The native browser runner executes original checks directly with shared settings, not generated source copies. Set Java 21 `JAVA_HOME` before the documented commands. Private credentials, roster input, backups and source baseline remain under ignored `.local/`.

Next exact action: open the local/public app or inspect `docs/CODE_REVIEW.md`. After future source edits, stop the local app before rebuilding, rerun affected checks, then restart it. All 48 core tasks and all 40 required acceptance checks are complete; public hosting remains a separate conditional task. Historical milestones below retain their original runtime PIDs and test counts.


## Current UI/UX closing window, 2026-10-10

- The user explicitly extended the same published UI/UX test (exam ID 3) to **10 October 2026, 02:00 AM Asia/Calcutta / IST**. The opening stays **01:30 AM** and the per-attempt duration stays **10 minutes**. UTC close is now `2026-10-09T20:30:00Z`. This supersedes the original 01:40 closing time documented below.
- Applied the specific operator-authorized amendment in one MySQL transaction, with exam ownership/identity/state validation and exam/attempt row locks. Updated only the closing time and its matching instructions; persisted an `EXAM_WINDOW_EXTENDED` audit event. Original publication timestamp, 30 questions, 120 options, keys, marks, existing deadlines, saved answers and finalized results were preserved. Existing finalized attempts remain terminal under the one-attempt policy.
- New attempts use the existing server policy: deadline is the earlier of start plus 10 minutes or closing time. Start by **01:50 AM IST** for the full 10 minutes; every attempt must end by 02:00 AM. The general published-authoring API remains immutable; this was a specifically requested operator calendar amendment, not an application-policy change.
- Local instructor readback compared the complete paper against the original private data. Browser checks showed the correct immutable schedule, 30-question count and student availability OPEN, with no answer keys or created verification attempt. Both verification sessions logged out; no public credential transmission was needed.
- Evidence is `evidence/uiux-extension-summary.json`, `evidence/uiux-extension-mysql.log`, `evidence/uiux-extension-browser.json` and two safe schedule/catalog screenshots. Private amendment helper is `.local/ExtendUiuxWindow.java`. Public student catalog remains https://graveness-undrafted-harsh.ngrok-free.dev/student.html; refresh it to load the extended window.

## Supplied student roster, 2026-10-10

- All 15 students from the supplied image are now enabled `STUDENT` accounts in persistent `online_exam`. Supplied names/spellings/case are preserved. Each student's roll number is both username and password; database credentials use the application's delegating bcrypt encoder.
- Private roster: `.local/student-roster.tsv` (UTF-8 name/tab/roll rows, no header). It is ignored and outside served resources; no student names, roll numbers, passwords or hashes were copied into evidence. See `docs/ACCOUNTS.md` for the Java 21 import command.
- `tools/SeedStudentRoster.java` is an explicit operator-only import for this numeric student credential scheme. It uses one MySQL transaction and prechecks existing matches; repeated runs retain accounts and conflicts abort without resets. General provisioning validation and the running application's security/session/timing behavior are unchanged.
- First import created 15 accounts. Repeat created zero and retained 15. An intentional existing-name conflict was rejected; the subsequent original-roster check confirmed all 15 names/roles/password matches remained intact. All 15 actual local API logins and a numeric browser login passed; student catalog access worked. Verification sessions logged out and zero exam attempts were started.
- Secret auditing now includes private eight-digit roster credentials as well as generated development/demo secrets. Evidence uses `evidence/student-roster-*`; only aggregate results are stored. The app and ngrok remain running; the earlier test's exact schedule remains unchanged.

## User UI/UX test, 2026-10-10

- Created and published **UI/UX Concepts and Principles - Basic Test**, exam ID 3, under `demo_instructor1` in the persistent `online_exam` schema. It contains 30 beginner questions on UI/UX, consistency, feedback, errors, hierarchy, accessibility, responsive design, research and navigation. Each has four distinct options, one correct option and one mark; total 30 marks with no negative marking.
- Duration is 10 minutes. Exact schedule: **10 October 2026, 01:30-01:40 AM Asia/Calcutta (IST)**; stored as `2026-10-09T20:00:00Z` to `2026-10-09T20:10:00Z`. All attempts end by closing time; starting late reduces the remaining time. Published schedule/content is immutable under existing application rules.
- Public catalog: https://graveness-undrafted-harsh.ngrok-free.dev/student.html. Browser verification showed Upcoming before opening, the correct question/marks/time counts, and exact instructor date fields. No verification attempt was started or consumed. The answer key stays server-side and in ignored private working files, outside evidence/static resources.
- MySQL verification confirms 30 questions, 120 options, exactly one correct option each, 30 marks, published status and exact UTC schedule. Evidence: `evidence/uiux-test-summary.json`, `evidence/uiux-test-mysql.log`, `evidence/uiux-test-browser.json` and the two safe schedule/catalog screenshots.
- The initial public browser check was rejected for credential transmission; the user specifically approved that action and the same check then passed. Both verification role sessions logged out. Existing examinations, attempts, app source and timing/security controls were preserved.

## Temporary public tunnel, 2026-10-10

- User explicitly authorized ngrok exposure of existing private exam/user data after automatic approval review initially rejected startup. The authorized retry succeeded without changing app settings or persistent data.
- Public URL: https://graveness-undrafted-harsh.ngrok-free.dev. Ngrok 3.39.11 PID 24044 forwards to the existing loopback app on 8080; traffic inspection is disabled. Private process/start-time and URL records are in `.local/ngrok-process.json` and `.local/ngrok-tunnel.json`.
- Public HTTPS checks passed for readiness UP, login HTML and the current dashboard stylesheet. Anonymous identity, exam and instructor result APIs returned 401. See `evidence/ngrok-public-smoke.json` and `evidence/ngrok-authorization.md`.
- TUNNEL-01 records this temporary sharing task separately from durable production hosting. Keep the machine, ngrok, Java app and MySQL running; after any tunnel restart retrieve and verify its URL again. No private credentials, record bodies or request cookies were copied to evidence.

## Dashboard update, 2026-10-09

- UI-01 replaces all five screens with conventional dashboard styling: neutral gray backgrounds, white panels, system typography, compact headings, blue actions and role-specific navigation. The student catalog is a semantic examination table that stacks at mobile widths. Login, authoring, timing, persistence and grading behavior are preserved.
- UI-01 is verified by V0055 behavior checks and V0056 final closure; B003 is RESOLVED. Fresh checks passed: 14 Java unit/HTTP tests, all four existing Chromium browser flows, keyboard/offline/safe-text/expiry acceptance reruns, and 16 layout views at 1440px and 360px with no clipping, duplicate IDs or page errors. Evidence uses the `evidence/dashboard-*` prefix; previous screenshot evidence was retained.
- The generic Windows test helper failed to deliver usable HTTP readiness and left its Java child running. B003 records the failure and repair. `python tools/run_dashboard_checks.py` starts the Java executable directly, writes its output to an ignored log, checks HTTP/database readiness and terminates its own process. Stop the development app before that command; it uses only `online_exam_e2e`.
- User/demo guides now match dashboard navigation and automatic answer saving. The new Java package is running on the same local URL; refresh with Ctrl+F5 to reload the stylesheet.

## Local runtime, 2026-10-09

- The application is running at `http://127.0.0.1:8080/`, bound to loopback, using the explicit `demo` profile and persistent `online_exam` schema. Its process record is `.local/local-app.json`; current PID is 36268. Docker MySQL 8.4.11 `online-exam-db-1` is healthy on `127.0.0.1:3307`.
- Run `powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/start-local.ps1` to start or check this app. Run the same command with `tools/stop-local.ps1` to stop it while retaining MySQL and its data. Stop before adding `-Build`, changing `-Profile`, or changing `-Port`. See the updated README for manual setup.
- Demo logins are `demo_instructor1`, `demo_instructor2`, and `demo_student1` through `demo_student3`. Read their password privately from `.local/demo-password.txt`; never print it in evidence. The development schema has Flyway V1 applied, two instructors, three students, and no exams or attempts. Create a paper in the instructor interface to begin.
- This workstation initially had no Compose database container or volume. Compose downloaded the pinned image and created its development volume; no database was reset or dropped. Existing `.env`, password files, application source and earlier trackers were preserved. The supplied folder still has no Git repository.
- Fresh local checks passed: packaged build with 14 Java unit/HTTP tests, integration profile with 68 real-MySQL tests, four Chromium browser cases, suite-selection audit, launcher start/repeat/stop, development-schema login/logout/CSRF smoke, and host-side JDBC connectivity. No test was skipped. Logs are `evidence/local-init-*`; source manifest is `evidence/local-init-source-manifest.txt`.
- `LOCAL-01` is VERIFIED in V0053, with final launcher/build and completion checks in V0054. It records this additional workstation task separately from the 48 completed core tasks. The running app and database are intentionally left available. The earlier release evidence below remains historical; the previous statement that the app was stopped applies to that earlier release check.

Next exact action: open `http://127.0.0.1:8080/` and sign in with a demo account. After source edits, stop the app, run the launcher with `-Build`, and rerun the relevant checks. Browser checks own port 8080, so stop the local app before running them.

## Release state, 2026-09-29

- Final integrated application gates passed in V0049 on the 132-file release source manifest `8c6a080efb5ba8703404af8eab4944cc51803b034911b2f896578fd55d35a521`: 14 Java unit/HTTP, 68 real-MySQL integration, and four Chromium tests, all selected and executed with zero failures/errors/skips.
- All 48 core tasks are VERIFIED and all 40 required acceptance checks are PASS; B001 and B002 are RESOLVED. `implementation_status` is `CORE_COMPLETE`. The strict post-update tracker gate passed with zero errors in `evidence/p11-04-completion-gate.log`.
- The runnable JAR is `target/online-exam-0.1.0-SNAPSHOT.jar`. [README.md](../README.md) gives the exact setup/build/start/test commands. [docs/FINAL_REPORT.md](../docs/FINAL_REPORT.md) summarizes results and limits. The local URL is `http://127.0.0.1:8080/` while the app runs.
- Docker MySQL 8.4.11 `online-exam-db-1` remains healthy on loopback port 3307. The packaged Java app was stopped after browser verification. `online_exam` is normal persistent local data; `online_exam_it` and `online_exam_e2e` are guarded test schemas. P10-02 created extra `online_exam_clean_p10_*` and `online_exam_restore_p10_*` schemas and private SQL backup under ignored `.local/` for inspection; it dropped no schema.
- This directory has no `.git` repository. `git status` fails; preserve the supplied files, review the deterministic source manifest and inspect trackers instead of claiming a Git diff.
- `tracking/tasks.json`, `tests.json`, `blockers.json` and `verification.json` are authoritative. `tracking/STATUS.md` is generated by the verification recorder. Run `node tools/validate_trackers.mjs` after changes and `--require-complete` only after P11-04. Do not mark code written as verified without actual evidence.
- Public deployment remains `NOT_REQUESTED`; no hosting target, domain, TLS certificate or authorization is configured. Two P12 tasks are conditional and outside the 48 core count. [docs/DEPLOYMENT.md](../docs/DEPLOYMENT.md) is a preparation runbook, not a live deployment claim.

## Release checks and continuity

- Full Java/MySQL log: `evidence/p11-02-final-maven-mysql.log`; browser log: `evidence/p11-02-final-browser.log`; actual suite-selection audit: `evidence/p11-02-final-test-selection.log`; source manifest: `evidence/p11-02-source-manifest.txt`.
- Clean source/schema, backup and restore: V0045; ten-student pilot: V0042; package startup: V0044; complete browser journey/action sweep: V0041 and V0049; privacy/security/resilience: V0037–V0040. All 40 test IDs point to passing runs and evidence paths.
- Private `.env` holds local Docker credentials, and ignored `.local/demo-password.txt` holds a demo password. Never print or commit those, the private SQL backup, cookies or CSRF tokens. `python tools/audit_secrets.py` passed after the final gates.
- Known limits: Flyway 11.7.2 warns that MySQL 8.4 is outside its certified range, though V1 migrated successfully in integration and clean setup. Spring Boot 3.5.16 needs a planned future upgrade. In-memory student sessions and throttle counters mean one Java instance is the tested deployment boundary. There is no camera/device proctoring.

Public deployment starts only after a real target and authorization are supplied; use the conditional runbook in `docs/DEPLOYMENT.md` then.
