# Final integrated release gates

- Release source manifest: SHA-256 `8c6a080efb5ba8703404af8eab4944cc51803b034911b2f896578fd55d35a521` over 132 source/build/config files; identical before and after these gates (`evidence/p11-01-source-manifest.txt`, `evidence/p11-02-source-manifest.txt`).
- `mvnw.cmd -B -ntp -Dmaven.repo.local=.local/m2 -Pmysql-it clean verify`: BUILD SUCCESS, 14 Surefire unit/HTTP and 68 Failsafe real-MySQL tests; zero failures, errors or skips. The executable JAR was rebuilt. MySQL is the pinned 8.4.11 local container and the guarded integration schema is `online_exam_it`.
- `npm.cmd --prefix e2e run test:e2e`: four Chromium cases passed against the rebuilt JAR and guarded `online_exam_e2e` schema, including UI-created paper, saved/reloaded answers, persisted result and owner review. The Playwright list and XML/report audit confirmed 14+68+4 selected/executed tests with zero skips.
- `python tools/check_documentation.py`: all 14 release Markdown files and 15 local links resolved; documented commands and API routes matched source. `python tools/audit_secrets.py`: no generated local secret or raw session/CSRF value in scanned source/evidence/logs.
- The required clean install, backup/restore, ten-student pilot, release startup, resilience and manual security/browser procedures were completed earlier on the same application source; their exact evidence is linked from T001–T040. B001/B002 remain resolved. No public hosting target is configured.

The Playwright runner emitted a Node `NO_COLOR`/`FORCE_COLOR` warning under PowerShell, but all four cases passed and there was no browser application error. Flyway still emits its documented MySQL 8.4 vendor support warning. Neither warning is represented as a failed check.
