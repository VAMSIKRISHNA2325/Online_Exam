# Online Examination System

Java 21, Spring Boot 3.5.16 and MySQL 8.4.11 application for instructor-authored single-answer MCQ exams. The UI is served from the Java application at `http://127.0.0.1:8080/`. A student can save answers, resume the original timed attempt, submit and see an aggregate result. Instructors create and publish papers and review results.

## Requirements

- Java 21 JDK (set `JAVA_HOME`), Docker Compose or a separate MySQL 8.4 server, and a shell. The included Maven Wrapper downloads pinned Maven 3.9.16 on its first use.
- Node 24 and npm are needed only to run JavaScript Playwright tests and tracker tooling; they are not application runtime dependencies.

## Local setup (PowerShell)

For the initialized Windows workspace, start the local demo with:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/start-local.ps1
```

The launcher reads your private `.env`, starts MySQL, and runs the packaged app in the background at `http://127.0.0.1:8080/`. Sign in as `demo_instructor1` or `demo_student1` using the private password in `.local/demo-password.txt`. Repeating the command checks the existing process. Add `-Build` after source changes, `-Profile default` to run without demo seeding, or `-Port 8081` to select another local port. Stop the app before changing these settings:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/stop-local.ps1
```

MySQL stays running and retains its data. App logs are in `.local/local-app-start.log` and `.local/local-app-error.log`. The commands below describe manual setup on a new machine.

1. Copy `.env.example` to `.env`; replace both password placeholders with different strong values. Run `docker compose up -d db` and check `docker compose ps` for a healthy `db` service. Compose retains data in `exam_mysql_data` across ordinary stops.
2. Set these variables in the **same shell** that will start the app. Set `DB_PASSWORD` to the `MYSQL_PASSWORD` value from your private `.env` without placing it in a command argument or checked-in file:

   ```powershell
   $env:JAVA_HOME = 'C:\path\to\jdk-21'
   $env:DB_URL = 'jdbc:mysql://127.0.0.1:3307/online_exam?connectionTimeZone=UTC'
   $env:DB_USER = 'online_exam'
   $env:DB_PASSWORD = '<private MYSQL_PASSWORD value>'
   ```

3. Build and start the executable JAR:

   ```powershell
   .\mvnw.cmd -B -ntp clean package
   & "$env:JAVA_HOME\bin\java.exe" -jar target/online-exam-0.1.0-SNAPSHOT.jar
   ```

   Flyway creates or updates tables on startup. Visit `http://127.0.0.1:8080/api/ready` for database readiness, then open `http://127.0.0.1:8080/`. The default profile creates **no** user accounts. See [account provisioning](docs/ACCOUNTS.md) to create instructors and students, or run the explicit demo profile with a private `DEMO_PASSWORD` to seed two instructors and three students. Do not run demo seeding in a public environment.

On macOS/Linux use `./mvnw -B -ntp clean package` and `java -jar target/online-exam-0.1.0-SNAPSHOT.jar` with the same environment variables. If you use native MySQL, create an `online_exam` database and a dedicated account with schema privileges, then set `DB_URL`, `DB_USER` and `DB_PASSWORD` for that server. [Database setup](docs/DATABASE_SETUP.md) has the isolated test schemas and safety rules.

## Verification

Stop the local app before Maven rebuilds on Windows so the running JAR can be replaced. Browser checks can use a separate port by setting `$env:E2E_PORT = '8081'`; they still use the disposable `online_exam_e2e` database.

For a real MySQL integration run, set `MYSQL_IT_PASSWORD` privately (or `MYSQL_PASSWORD` from `.env`). The tests default to the **disposable** `online_exam_it` schema and reject a different schema. Run:

```powershell
.\mvnw.cmd -B -ntp -Pmysql-it verify
```

The Maven profile runs Java unit/HTTP tests and MySQL integration tests. The browser suite uses a separate `online_exam_e2e` schema, a private `DEMO_PASSWORD`, Node, and matching Chromium:

```powershell
npm.cmd --prefix e2e ci
npm.cmd --prefix e2e run install:chromium
npm.cmd --prefix e2e run test:e2e
```

Additional keyboard, offline, safe-text, responsive and restart checks use Python with `requests` and Playwright, plus the Chromium installed above:

```powershell
$env:E2E_PORT = '8081'
python tools/run_dashboard_checks.py
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/check_restart_recovery.ps1
python tools/rehearse_clean_setup_backup.py
```

The clean setup check builds a fresh source copy, migrates a new test schema and verifies a backup restored into another new schema. It retains those schemas and its private backup under `.local/` for inspection. [Code review](docs/CODE_REVIEW.md) describes the simplifications and verification from the latest refactor.

Do not point test runners at the normal `online_exam` schema. Run `node tools/validate_trackers.mjs --require-complete` to validate the final evidence ledger; this utility does not execute tests. Detailed setup, API, architecture, use and backup instructions are in `docs/`.

## Limits

This first release assumes one application instance. Sessions and login throttling are held in that process; the exam, saved answers, audit events and results are stored in MySQL. Timing and scoring are controlled by the server. Public deployment needs a separately configured and authorized host. Flyway 11.7.2 logs a vendor support warning for MySQL 8.4, although the migrations are exercised against the pinned local MySQL image.
