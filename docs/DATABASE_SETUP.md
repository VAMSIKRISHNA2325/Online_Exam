# Local MySQL setup

The Compose project `online-exam` uses MySQL 8.4.11 on loopback port 3307 by default. It creates a persistent development schema `online_exam` plus separate disposable schemas `online_exam_it` and `online_exam_e2e`. Automated tests must positively check their expected disposable schema before clearing fixtures.

1. Copy `.env.example` to `.env`. Replace the two password placeholders with distinct strong local passwords. `.env` is Git ignored.
2. Run `docker compose up -d db`, then `docker compose ps`. The `db` service must report healthy.
3. Set `DB_URL=jdbc:mysql://localhost:3307/online_exam?connectionTimeZone=UTC`, `DB_USER=online_exam`, and `DB_PASSWORD` to the `MYSQL_PASSWORD` value in the same process that launches the Java application. Maven does **not** load `.env` for Spring Boot.
4. For integration tests, use `jdbc:mysql://localhost:3307/online_exam_it?connectionTimeZone=UTC`. For browser fixtures, use `online_exam_e2e` only.

Windows PowerShell example, after setting `$env:JAVA_HOME` to a Java 21 JDK and setting `DB_PASSWORD` in the process without printing it:

```powershell
$env:DB_URL = 'jdbc:mysql://localhost:3307/online_exam?connectionTimeZone=UTC'
$env:DB_USER = 'online_exam'
.\mvnw.cmd spring-boot:run
```

Do not run destructive test fixtures against `online_exam` or another database. `docker compose down` stops this project's container and preserves its named volume; `docker compose down -v` deletes its data and is outside normal operation. If Docker is unavailable, run an isolated native MySQL 8.4.11 instance, create the same three schemas and grant a dedicated local user only those schemas. Use the same `DB_URL`, `DB_USER` and `DB_PASSWORD` variables to reach it. The native path still requires actual MySQL verification.
