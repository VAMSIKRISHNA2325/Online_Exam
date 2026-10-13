# Online Examination System

Java 21, Spring Boot 3.5.16 and MySQL 8.4.11 application for instructor-authored single-answer MCQ exams. The UI is served from the Java application at `http://127.0.0.1:8080/`. A student can save answers, resume the original timed attempt, submit and see an aggregate result. Instructors create and publish papers and review results.

## Requirements

- A Java 21 **JDK** (not just a JRE), Docker Desktop with Compose on Windows/macOS or Docker Engine with Compose on Linux, and an internet connection for the first Docker image and Maven dependency downloads. Start Docker before launching the app. The included Maven Wrapper downloads pinned Maven 3.9.16; a separate Maven installation is unnecessary.
- Node 24 and npm are needed only for the JavaScript Playwright tests and tracker tooling. Python 3 is needed only for the optional diagnostic and backup rehearsal scripts. Neither is needed to run the Java app.

## Set up on a new device (Windows)

1. Clone or copy this repository to the new device and open PowerShell in its root folder (the folder containing `pom.xml` and `compose.yaml`). A clone contains source and sample configuration; it does not contain private passwords or MySQL data.
2. Install a Java 21 JDK and Docker Desktop. Start Docker Desktop. Set `JAVA_HOME` to the JDK folder for this PowerShell session and check both tools:

```powershell
$env:JAVA_HOME = 'C:\path\to\your\jdk-21'
& "$env:JAVA_HOME\bin\java.exe" -version
docker compose version
docker info
```

3. Create the private Docker configuration. Open `.env` in an editor, replace **both** password placeholders with different strong values, save it, and keep `MYSQL_PORT=3307` unless that port is unavailable. The file is Git ignored. Do not commit it or paste its passwords into an issue or terminal command.

```powershell
Copy-Item .env.example .env
```

4. Build and start the local demo from the repository root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/start-local.ps1 -Build
```

The launcher reads `.env`, builds the JAR with the Maven Wrapper, pulls/starts MySQL 8.4.11 with Docker Compose, applies Flyway migrations, and starts the app on loopback. On a fresh database it creates two demo instructors and three demo students. It generates a private demo password at `.local/demo-password.txt`; use that password to sign in as `demo_instructor1` or `demo_student1`. The first build may take longer while dependencies download. Visit `http://127.0.0.1:8080/api/ready` to check database readiness, then open `http://127.0.0.1:8080/`.

For later PowerShell sessions, set `JAVA_HOME` again or configure it as a persistent user environment variable.

The app runs in the background. Repeating the launcher checks its recorded process. Stop that process before rebuilding, changing its profile or changing its port:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/stop-local.ps1
```

MySQL stays running and retains its named volume. App logs are in `.local/local-app-start.log` and `.local/local-app-error.log`. Run `tools/start-local.ps1 -Build` after source changes; use `-Profile default` for an empty-account normal profile or `-Port 8081` for another local port. The default profile needs [account provisioning](docs/ACCOUNTS.md) before anyone can sign in.

**Moving an existing installation:** A Git clone does not transfer the old device's `.env`, `.local/` files or MySQL volume. To retain existing accounts, exams, answers and results, make and verify a private MySQL backup on the old device and restore it on the new one; see [backup and restore](docs/BACKUP_RESTORE.md). Its disposable-schema rehearsal is a check, not a command for importing your live database. Keep the matching private demo password if you intend to start the restored database with the `demo` profile, because demo startup checks existing account credentials. Do not transfer a stale `.local/local-app.json` process record. A fresh database starts without the old records.

## Manual startup and other operating systems

On Windows, the launcher above is the shortest path. To run the default profile manually, start MySQL with `docker compose up -d db` and check `docker compose ps` for a healthy `db` service. Set these variables in the **same shell** that starts Java. `DB_PASSWORD` must equal the private `MYSQL_PASSWORD` in `.env`; Maven and Spring Boot do not load `.env` automatically:

```powershell
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3307/online_exam?connectionTimeZone=UTC'
$env:DB_USER = 'online_exam'
$env:DB_PASSWORD = (Select-String -Path .env -Pattern '^MYSQL_PASSWORD=').Line.Split('=', 2)[1]
.\mvnw.cmd -B -ntp clean package
& "$env:JAVA_HOME\bin\java.exe" -jar target/online-exam-0.1.0-SNAPSHOT.jar
```

Flyway creates or updates tables at startup. The default profile creates **no** accounts; use [account provisioning](docs/ACCOUNTS.md), or set a private 12–128 character `DEMO_PASSWORD` and add `--spring.profiles.active=demo` to the JAR command for the explicit demo profile. Do not use demo seeding in a public environment.

On macOS/Linux, use Bash from the repository root. First copy the sample:

```bash
cp .env.example .env
```

Edit and save `.env` with two different private passwords **before** running the next block. Then:

```bash
docker compose up -d --wait db
docker compose ps
export JAVA_HOME='/absolute/path/to/jdk-21'
export DB_URL='jdbc:mysql://127.0.0.1:3307/online_exam?connectionTimeZone=UTC'
export DB_USER='online_exam'
read -r -s -p 'MYSQL_PASSWORD from .env: ' DB_PASSWORD; printf '\n'
export DB_PASSWORD
./mvnw -B -ntp clean package
read -r -s -p 'Choose a private demo password (12-128 characters): ' DEMO_PASSWORD; printf '\n'
export DEMO_PASSWORD
java -jar target/online-exam-0.1.0-SNAPSHOT.jar --spring.profiles.active=demo
```

Keep the demo password privately for later sign-ins/restarts. Omit the `DEMO_PASSWORD` prompt and `--spring.profiles.active=demo` for the default profile, then provision accounts separately. If `MYSQL_PORT` differs from 3307, change the JDBC URL accordingly. For a native MySQL server, create an `online_exam` schema and a dedicated user with privileges on it; point the three DB variables at that server. [Database setup](docs/DATABASE_SETUP.md) covers the isolated test schemas and safety rules.

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
