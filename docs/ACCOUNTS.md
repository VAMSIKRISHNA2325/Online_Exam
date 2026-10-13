# Demo accounts and local provisioning

Accounts are stored in MySQL, and passwords are encoded before insertion. There is no public registration route. The normal application profile does not seed accounts.

## Explicit demo profile

Set `DB_URL`, `DB_USER` and `DB_PASSWORD` as described in [DATABASE_SETUP.md](DATABASE_SETUP.md). Set `DEMO_PASSWORD` to a private 12 to 128 character value, then start the app with the `demo` profile:

```powershell
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3307/online_exam?connectionTimeZone=UTC'
$env:DB_USER = 'online_exam'
# Set DB_PASSWORD and DEMO_PASSWORD privately in this process.
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=demo'
```

The profile creates two instructors, `demo_instructor1` and `demo_instructor2`, and three students, `demo_student1`, `demo_student2` and `demo_student3`. Each uses the supplied demo password. Repeating startup leaves matching accounts intact. Startup fails if a name already exists with another role or password; it does not overwrite credentials. Keep `DEMO_PASSWORD` out of command arguments, logs and source control.

## Provision a separate account locally

The `provision` profile runs without a web server. With the same database variables configured, set these four additional environment variables privately:

| Variable | Rule |
|---|---|
| `PROVISION_USERNAME` | 3 to 80 letters/digits/dot/dash/underscore, starting with a letter; normalized to lowercase |
| `PROVISION_DISPLAY_NAME` | 1 to 120 characters |
| `PROVISION_ROLE` | `STUDENT` or `INSTRUCTOR` |
| `PROVISION_PASSWORD` | 12 to 128 characters |

Then run:

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=provision'
```

The command creates one account and prints its username and role only. Duplicate usernames fail. Remove `PROVISION_PASSWORD` from the process environment after use. Provisioning against `online_exam_it` or `online_exam_e2e` is only for disposable test fixtures.

## Explicit student roll-number roster import

On 10 October 2026, the supplied 15-student roster was imported into the persistent development database. Each student's supplied display name is preserved. Their eight-digit roll number is both their username and their password; the database stores an application-compatible bcrypt hash. The roster is private at `.local/student-roster.tsv`, outside source control and the served web resources.

`tools/SeedStudentRoster.java` handles this specifically authorized student import. Supply a UTF-8 file containing one `display name<TAB>roll number` row per student, without a header. It targets only `online_exam`, creates enabled `STUDENT` accounts in one transaction, and prints aggregate counts. A repeated import retains matching accounts; a conflicting name, role or password aborts the transaction without resetting an account. Standard account provisioning above retains its existing validation rules.

With Java 21, `DB_URL`, `DB_USER` and `DB_PASSWORD` configured privately for the development schema, and the initialized Maven cache available, run:

```powershell
$jdbcJar = Get-ChildItem '.local/m2/com/mysql/mysql-connector-j/*/*.jar' | Select-Object -First 1
$cryptoJar = Get-ChildItem '.local/m2/org/springframework/security/spring-security-crypto/*/*.jar' | Select-Object -First 1
$loggingJar = Get-ChildItem '.local/m2/org/springframework/spring-jcl/*/*.jar' | Select-Object -First 1
$rosterClassPath = @($jdbcJar.FullName, $cryptoJar.FullName, $loggingJar.FullName) -join ';'
& "$env:JAVA_HOME/bin/java.exe" --class-path $rosterClassPath tools/SeedStudentRoster.java .local/student-roster.tsv
```

The running application reads these accounts immediately. Seed verification checked every supplied name/role/password match and all 15 local sign-ins, plus numeric login through the browser, without starting exam attempts. Keep the roster and any derived credentials private; evidence contains only counts and outcomes.

## Input and browser boundaries

The login endpoint rejects usernames over 80 characters and passwords over 128 characters before account lookup or password verification. Exam titles, descriptions, questions and options have server-side length checks; durations are 1 to 10,080 minutes and question marks are 1 to 1,000. The instructor form uses the same numeric limits. User text is stored as data through parameterized JDBC statements and rendered with DOM text nodes. A Content Security Policy allows application scripts and styles from this origin and blocks inline scripts, objects and embedding. Invalid API input returns a fixed error without database details.

The local secret audit can be rerun with `python tools/audit_secrets.py` after creating the ignored `.env` and `.local/demo-password.txt` fixtures. It checks generated local secrets in repository text, evidence and the current `.local/p08-02-app-start.log` without printing the secret values.

## Failed sign-in throttling

The running application allows five incorrect passwords for one account from one source IP during a rolling one-minute window. Further login attempts, including a correct password, receive HTTP `429` with code `LOGIN_THROTTLED` and a `Retry-After` header for a 30-second cooldown. A successful login clears its failure count. Once the cooldown ends, the user may retry with the real password; the cooldown never authenticates a user by itself. Other accounts remain available from the same IP.

A second valid student login blocked by the one-session rule returns `409 SESSION_IN_USE` and does not count as an incorrect password. It preserves the first session and writes a `DUPLICATE_LOGIN` event without credentials or session IDs. If the first browser still has its cookie, log out there. If its cookie was lost, wait for the documented idle-session expiry or use supervised instructor recovery for an active attempt.

The counters are bounded in memory to 4096 account/source pairs and apply to this single application instance. Restarting the app clears them; multiple application instances do not share counters. The cooldown defaults can be configured with `exam.auth.throttle.max-failures`, `exam.auth.throttle.window` and `exam.auth.throttle.cooldown` using a positive count and ISO-8601 durations. The application rejects invalid values on startup. This control slows repeated guesses; it is not a distributed abuse-defense service.

## Supervised session recovery

For a student with a current attempt who has lost their session cookie, the instructor who owns that exam can open the published paper in the instructor desk and choose **Reset session** beside the student's active attempt. The confirmation names the student and explains that the old login ends while saved answers and the original deadline stay in place. The student then signs in again and resumes the same attempt. A request after the original deadline cannot extend the exam.

The CSRF-protected `POST /api/attempts/{attemptId}/session-reset` accepts only the owning instructor. It rejects a terminal or expired attempt and rejects recovery if the student has another current attempt owned by a different instructor. It records a `SESSION_RECOVERY` event with the instructor actor, exam and attempt IDs and a fixed detail without credentials, session IDs or tokens. The old session is marked expired in Spring Security's registry and is rejected on its next request. Login registration and recovery use the same in-process lock so a concurrent login cannot bypass the one-student-session rule. Recovery is available only while this single application instance holds the relevant session registry; an application restart clears old in-memory sessions, after which the student can sign in and resume directly from MySQL.
