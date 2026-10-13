# Local release report

## Delivered

The Online Examination System is a runnable Java 21/Spring Boot 3.5.16 application with vanilla HTML/CSS/JavaScript and MySQL 8.4.11 persistence. Instructors own draft creation, question editing, publication, results and scoped events. Students see published exams, take one timed single-answer MCQ attempt, save/clear with revisions, resume the original deadline, submit, and reopen a server-graded aggregate result. A second student login is denied while preserving the first. The owning instructor can recover a lost live session without resetting the attempt. Flyway V1 creates the schema; normal startup seeds no accounts. The `demo` and `provision` profiles are explicit.

## Executed release checks

| Gate | Exact command or procedure | Result |
|---|---|---|
| Java/MySQL full gate | `.\mvnw.cmd -B -ntp -Dmaven.repo.local=.local/m2 -Pmysql-it clean verify` | BUILD SUCCESS; 14 unit/HTTP and 68 real-MySQL tests, zero failures/errors/skips (`evidence/p11-02-final-maven-mysql.log`) |
| Browser gate | `npm.cmd --prefix e2e run test:e2e` | Four Chromium cases passed against isolated MySQL and packaged JAR (`evidence/p11-02-final-browser.log`) |
| Selection audit | `python tools/check_test_selection.py --maven-log evidence/p11-02-final-maven-mysql.log --playwright-list evidence/p11-02-final-playwright-list.log --playwright-run evidence/p11-02-final-browser.log` | 14+68+4 actually selected/executed, zero skips (`evidence/p11-02-final-test-selection.log`) |
| Clean installation and backup/restore | `python tools/rehearse_clean_setup_backup.py` | Fresh source build and new MySQL schema startup passed; separate restore matched nine table counts and all ordered row data (`evidence/p10-02-clean-setup-backup.log`) |
| Ten-student pilot | `powershell -NoProfile -ExecutionPolicy Bypass -File tools/run_ten_student_pilot.ps1` | Ten distinct concurrent students, 20 acknowledged answers and ten independent results reconciled in MySQL; local wall 0.98 s, p95 0.33 s (`evidence/p09-03-ten-student-pilot.log`) |
| Package smoke | `powershell -NoProfile -ExecutionPolicy Bypass -File tools/check_release_startup.ps1` | External DB configuration, readiness 200, login page 200, anonymous `/api/me` 401 (`evidence/p10-01-release-startup-passing.log`) |
| Documentation/secrets | `python tools/check_documentation.py`; `python tools/audit_secrets.py` | 14 Markdown files/15 links and documented commands/routes validated; no generated local secrets or raw session/CSRF values found (`evidence/p11-02-final-doc-check.log`, `evidence/p11-02-final-secret-audit.log`) |

All 48 core tasks are VERIFIED and all 40 required acceptance checks are PASS in `tracking/`; both observed blockers are RESOLVED. The strict `node tools/validate_trackers.mjs --require-complete` gate passed with zero errors after setting `CORE_COMPLETE` (`evidence/p11-04-completion-gate.log`). The 132-file release source manifest has SHA-256 `8c6a080efb5ba8703404af8eab4944cc51803b034911b2f896578fd55d35a521` before and after the final application gates. This workspace has no `.git` repository; a Git diff or commit reference is unavailable. The manifest and `tracking/verification.json` provide the source/evidence record.

## Run and demonstrate

Start with [README.md](../README.md): configure Java 21 and a private MySQL password, run `docker compose up -d db`, build `target/online-exam-0.1.0-SNAPSHOT.jar`, then execute `java -jar target/online-exam-0.1.0-SNAPSHOT.jar` with `DB_URL`, `DB_USER` and `DB_PASSWORD` set in the process environment. The local URL while running is **http://127.0.0.1:8080/** and database readiness is **http://127.0.0.1:8080/api/ready**. The Java process was stopped after verification; Docker MySQL remained running. For accounts and a guided demonstration use [ACCOUNTS.md](ACCOUNTS.md) and [DEMO_GUIDE.md](DEMO_GUIDE.md). The ordinary profile does not install demo users.

## Limits and handoff

This release is for one application instance. Sessions and login throttling are in memory; exam, answer, revision, event and result records are in MySQL. The product does not provide camera/device proctoring or identity proof beyond login. Flyway 11.7.2 emits a vendor support warning for MySQL 8.4 even though tested migrations passed; plan a supported migration path for future schema changes. Spring Boot 3.5.16 also needs a maintenance upgrade plan for long-term service. The local ten-student pilot is an observed demo workload, not a capacity guarantee.

Public deployment is `NOT_REQUESTED`: there is no authorized host, domain or TLS configuration, so no public URL is claimed. [DEPLOYMENT.md](DEPLOYMENT.md) and `deploy/runtime.env.example` provide the conditional hosting checklist. To resume work, read [tracking/HANDOFF.md](../tracking/HANDOFF.md), inspect the JSON trackers, and run the README's exact verification commands after any relevant source change.
