# Execution environment

Observed 2026-09-29 from `D:\CREATIONS\Online_Exam`.

| Item | Observation |
|---|---|
| Repository | Specification and initialized trackers only; `git status --short` reports this directory is not a Git repository. |
| OS/shell | Windows, PowerShell 5.1.26100.9549. |
| Java/Maven | Neither is on PATH. Portable Temurin 21.0.12.1+1 and Maven 3.9.16 were downloaded into ignored `.local/` after inspection. Their version commands passed with `JAVA_HOME` set to the portable JDK. |
| Node/npm | Node 24.13.1; `npm.cmd` 11.8.0. `npm.ps1` is blocked by PowerShell execution policy, so use `npm.cmd`. |
| Browser | Microsoft Edge 154.0.4258.37 and Chrome 153.0.8010.54 installed; Playwright package not yet installed. |
| MySQL | `mysql` is absent from PATH; no MySQL service was found. No listener was found on port 3306. |
| Docker | CLI 29.7.2 installed. The restricted shell cannot access the engine; a sandbox-escalated `docker info` reaches engine 29.7.2. |
| Network | Restricted shell cannot reach Maven Central. An elevated sandbox `curl.exe -I` received HTTP 200 from Maven Central. |

At the initial inspection no application service or database was started. The portable Temurin ZIP SHA-256 `f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e` matches the [Adoptium asset API](https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&heap_size=normal&image_type=jdk&jvm_impl=hotspot&os=windows&vendor=eclipse).

## Release check update, 2026-09-29

The initial table above is a point-in-time inspection. Java 21, Maven Wrapper, Docker MySQL 8.4.11 and pinned Playwright/Chromium were subsequently installed and used. The executable JAR ran in the normal profile (V0044); a fresh source copy migrated a new schema and passed startup (V0045); real-MySQL and browser suites passed. The Java app was stopped after checks while Docker MySQL remains available. This folder has no `.git` repository, so a Git diff cannot be produced here; source manifests and tracker verification records preserve the executed state.

P01-02 subsequently started project-owned MySQL 8.4.11 at `127.0.0.1:3307` and verified a host-side JDBC connection to development, integration and browser-test schemas. See `evidence/V0006-connect.log`. P01-03 ran a packaged Java application on `localhost:8080` and verified readiness with the database up, down and restored.

## Version choices

| Component | Pinned version | Compatibility/source |
|---|---|---|
| JDK | Eclipse Temurin 21.0.12.1+1 | Local version command and Adoptium checksum verified. |
| Maven | 3.9.16 | Local version command and official SHA-512 checksum verified; [current stable Maven 3 release](https://maven.apache.org/download.cgi). |
| Maven Wrapper | 3.3.4 | [Current stable wrapper](https://maven.apache.org/tools/wrapper/download.cgi); installed and used in P01-01. |
| Spring Boot | 3.5.16 | [Published release](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now); its [Maven plugin requires Maven 3.6.3+](https://docs.spring.io/spring-boot/3.5/maven-plugin/index.html). |
| Spring Security, MVC, JDBC, MySQL Connector/J, Flyway, JUnit | Spring Boot 3.5.16 managed versions | Use [Boot managed coordinates](https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html), including Flyway Core/MySQL 11.7.2. No independently floating Spring versions. |
| MySQL server | 8.4.11 | [MySQL 8.4.11 release notes](https://dev.mysql.com/doc/relnotes/mysql/8.4/en/news-8-4-11.html); exact container tag `mysql:8.4.11` is running locally. |
| Playwright test package | 1.63.0 | [Published Playwright release](https://github.com/microsoft/playwright/releases/tag/v1.63.0); lock via `e2e/package-lock.json` in the browser phase. |
| Node/npm | 24.13.1 / 11.8.0 observed | Browser/tracker tooling only. |

Boot 3.5.16 is its [last open-source maintenance release](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now). The project should schedule a Boot 4 migration before long-term operation; this choice favors the known 3.5 API for the first local release and is recorded as a maintenance risk.

**Migration compatibility caveat:** Flyway 11.7.2 starts against MySQL 8.4.11 but logs that 8.4 is newer than its tested MySQL range. The [Redgate support matrix](https://documentation.red-gate.com/fd/supported-database-versions-143754067.html) does not list MySQL 8.4 among its certified MySQL versions. This is a vendor-support gap, not an observed migration failure: V1 migrated real MySQL in integration tests and in the V0045 clean install. Future migrations and vendor certification remain maintenance work.
