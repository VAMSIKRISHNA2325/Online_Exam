"""Exercise a fresh source build/schema and a separate MySQL backup/restore.

Uses only disposable schemas created for this run. Leaves them and the private SQL
backup under ignored .local/ for inspection; never drops an existing schema.
"""

import hashlib
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timezone


ROOT = Path(__file__).resolve().parent.parent
LOCAL = ROOT / ".local"
CONTAINER = "online-exam-db-1"
SOURCE = "online_exam_e2e"
STAMP = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
CLEAN_DB = "online_exam_clean_p10_" + STAMP
RESTORE_DB = "online_exam_restore_p10_" + STAMP
assert all(name.replace("_", "").isalnum() for name in (CLEAN_DB, RESTORE_DB))


def private_settings():
    result = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.startswith("#"):
            key, value = line.split("=", 1)
            result[key] = value
    return result


SETTINGS = private_settings()
PORT = int(os.environ.get("E2E_PORT", "8080"))
assert 1024 <= PORT <= 65535, "Invalid E2E_PORT"
BASE = f"http://127.0.0.1:{PORT}"


def docker_mysql(user, sql, *, database=None, input_bytes=None, dump=False, extra=()):
    env = os.environ.copy()
    env["MYSQL_PWD"] = SETTINGS["MYSQL_ROOT_PASSWORD" if user == "root" else "MYSQL_PASSWORD"]
    program = "mysqldump" if dump else "mysql"
    command = ["docker", "exec", "-i", "-e", "MYSQL_PWD", CONTAINER, program,
               "-u", user, *extra]
    if database:
        command.append(database)
    if sql is not None:
        command.extend(["-Nse", sql])
    done = subprocess.run(command, input=input_bytes, capture_output=True, env=env)
    if done.returncode:
        raise RuntimeError(f"{program} failed for {database or 'server'} (exit {done.returncode})")
    return done.stdout


def create_disposable_database(name):
    docker_mysql("root", f"CREATE DATABASE `{name}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; "
                 f"GRANT ALL PRIVILEGES ON `{name}`.* TO 'online_exam'@'%';")


def copy_source():
    destination = LOCAL / ("clean-install-p10-" + STAMP)
    destination.mkdir(parents=True, exist_ok=False)
    for name in ("src", ".mvn", "docs", "docker"):
        shutil.copytree(ROOT / name, destination / name)
    for name in ("mvnw", "mvnw.cmd", "pom.xml", "README.md", ".env.example", "compose.yaml"):
        shutil.copy2(ROOT / name, destination / name)
    return destination


def clean_build_and_start(destination):
    env = os.environ.copy()
    java_home = Path(os.environ.get("JAVA_HOME") or ROOT / ".local/jdk/jdk-21.0.12.1+1")
    env.update(JAVA_HOME=str(java_home), DB_URL=f"jdbc:mysql://127.0.0.1:3307/{CLEAN_DB}?connectionTimeZone=UTC",
               DB_USER="online_exam", DB_PASSWORD=SETTINGS["MYSQL_PASSWORD"], DEBUG="false", PORT=str(PORT))
    maven = [str(destination / ("mvnw.cmd" if os.name == "nt" else "mvnw")),
             "-B", "-ntp", f"-Dmaven.repo.local={LOCAL / 'm2'}", "clean", "package"]
    prefix = os.environ.get("EVIDENCE_PREFIX", "p10-02")
    build_log = ROOT / "evidence" / f"{prefix}-clean-build.log"
    with build_log.open("wb") as output:
        done = subprocess.run(maven, cwd=destination, env=env, stdout=output,
                              stderr=subprocess.STDOUT)
    if done.returncode:
        raise RuntimeError(f"clean source build failed (exit {done.returncode}); inspect {build_log.name}")
    jar = destination / "target" / "online-exam-0.1.0-SNAPSHOT.jar"
    if not jar.is_file():
        raise RuntimeError("clean source build did not produce executable JAR")
    app_log = LOCAL / "p10-02-clean-app.log"
    with app_log.open("wb") as output:
        process = subprocess.Popen([str(java_home / "bin" / ("java.exe" if os.name == "nt" else "java")),
                                    "-jar", str(jar), "--server.address=127.0.0.1"], cwd=destination, env=env,
                                   stdout=output, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        try:
            for _ in range(90):
                if process.poll() is not None:
                    raise RuntimeError(f"clean app exited early ({process.returncode}); inspect {app_log.name}")
                try:
                    with urllib.request.urlopen(BASE + "/api/ready", timeout=2) as response:
                        if response.status == 200:
                            break
                except Exception:
                    time.sleep(1)
            else:
                raise RuntimeError("clean app did not become ready")
            with urllib.request.urlopen(BASE + "/login.html", timeout=5) as response:
                if response.status != 200 or b"Examroom" not in response.read():
                    raise RuntimeError("clean app did not serve login page")
            version = docker_mysql("online_exam", "SELECT version FROM flyway_schema_history "
                                   "WHERE success=1 ORDER BY installed_rank DESC LIMIT 1", database=CLEAN_DB).decode().strip()
            users = docker_mysql("online_exam", "SELECT COUNT(*) FROM users", database=CLEAN_DB).decode().strip()
            if version != "1" or users != "0":
                raise RuntimeError(f"clean database was not migrated/empty as expected (version={version}, users={users})")
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
    return hashlib.sha256(jar.read_bytes()).hexdigest()


def backup_restore():
    backup = LOCAL / ("p10-02-backup-" + STAMP + ".sql")
    dump_options = ("--single-transaction", "--quick", "--order-by-primary", "--set-gtid-purged=OFF",
                    "--skip-comments", "--skip-dump-date")
    backup.write_bytes(docker_mysql("online_exam", None, database=SOURCE, dump=True, extra=dump_options))
    if backup.stat().st_size < 1000:
        raise RuntimeError("backup unexpectedly empty")
    create_disposable_database(RESTORE_DB)
    docker_mysql("online_exam", None, database=RESTORE_DB, input_bytes=backup.read_bytes())
    data_options = (*dump_options, "--no-create-info", "--compact")
    original = docker_mysql("online_exam", None, database=SOURCE, dump=True, extra=data_options)
    restored = docker_mysql("online_exam", None, database=RESTORE_DB, dump=True, extra=data_options)
    if original != restored:
        raise RuntimeError("restored table data differs from source")
    counts = {}
    for table in ("users", "exams", "questions", "question_options", "attempts", "answers",
                  "answer_revisions", "security_events", "flyway_schema_history"):
        source_count = docker_mysql("online_exam", f"SELECT COUNT(*) FROM `{table}`", database=SOURCE).decode().strip()
        restored_count = docker_mysql("online_exam", f"SELECT COUNT(*) FROM `{table}`", database=RESTORE_DB).decode().strip()
        if source_count != restored_count:
            raise RuntimeError(f"restored row count differs for {table}")
        counts[table] = int(source_count)
    return backup.stat().st_size, hashlib.sha256(original).hexdigest(), counts


def main():
    with socket.socket() as probe:
        assert probe.connect_ex(("127.0.0.1", PORT)) != 0, "Test port is occupied; select a free E2E_PORT"
    create_disposable_database(CLEAN_DB)
    destination = copy_source()
    jar_hash = clean_build_and_start(destination)
    size, data_hash, counts = backup_restore()
    print(f"PASS: clean source copy {destination.name} built Java 21 executable JAR (sha256 {jar_hash}); "
          f"fresh {CLEAN_DB} migrated to Flyway V1 with zero accounts and served ready/login HTTP.")
    print(f"PASS: {SOURCE} single-transaction backup ({size} bytes) restored into separate {RESTORE_DB}; "
          f"ordered table data sha256 {data_hash} identical; row counts {counts}.")
    print("Normal online_exam database was not selected for clean setup, dump or restore; no schema was dropped.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
