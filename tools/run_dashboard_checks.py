"""Run dashboard browser checks on an isolated MySQL-backed Java process."""

import os
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parent.parent
LOCAL = ROOT / ".local"
LOCAL.mkdir(exist_ok=True)
settings = {}
for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
    if "=" in line and not line.startswith("#"):
        key, value = line.split("=", 1)
        settings[key] = value
password = (LOCAL / "demo-password.txt").read_text(encoding="utf-8").strip()
assert settings.get("MYSQL_PASSWORD") and password, "Private local credentials required"
jar = ROOT / "target/online-exam-0.1.0-SNAPSHOT.jar"
assert jar.is_file(), "Build the packaged application first"
java = str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe") if os.environ.get("JAVA_HOME") else shutil.which("java")
assert java, "Java 21 required"
port = int(os.environ.get("E2E_PORT", "8080"))
assert 1024 <= port <= 65535, "Invalid E2E_PORT"
base = f"http://127.0.0.1:{port}"
with socket.socket() as probe:
    assert probe.connect_ex(("127.0.0.1", port)) != 0, "Test port is occupied; select a free E2E_PORT"
browser_path = subprocess.check_output(
    ["node", "-e", "console.log(require('./e2e/node_modules/playwright').chromium.executablePath())"],
    cwd=ROOT, text=True,
).strip()
env = os.environ.copy()
env.update(
    DB_URL=f"jdbc:mysql://127.0.0.1:{int(settings.get('MYSQL_PORT', '3307'))}/online_exam_e2e?connectionTimeZone=UTC",
    DB_USER="online_exam", DB_PASSWORD=settings["MYSQL_PASSWORD"],
    DEMO_PASSWORD=password, DEBUG="false", PORT=str(port),
    E2E_PORT=str(port), E2E_BROWSER=browser_path,
)

checks = [
    ("keyboard", ROOT / "tools/check_keyboard_offline_ui.py"),
    ("safe-text", ROOT / "tools/check_safe_text_ui.py"),
    ("resilience", ROOT / "tools/check_resilience_browser.py"),
    ("layouts", ROOT / "tools/check_dashboard_layouts.py"),
]

with (LOCAL / "dashboard-test-app-start.log").open("w", encoding="utf-8") as output:
    server = subprocess.Popen(
        [java, "-jar", str(jar), "--spring.profiles.active=demo", "--server.address=127.0.0.1"],
        cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT,
        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
    )
    try:
        deadline = time.monotonic() + 60
        ready = False
        while time.monotonic() < deadline:
            if server.poll() is not None:
                raise RuntimeError("Isolated app exited; inspect .local/dashboard-test-app-start.log")
            try:
                response = requests.get(base + "/api/ready", timeout=2)
                if response.status_code == 200 and response.json().get("status") == "UP":
                    ready = True
                    break
            except (requests.RequestException, ValueError):
                pass
            time.sleep(.3)
        assert ready, "Isolated app never reached HTTP/database readiness"
        print("PASS: isolated online_exam_e2e Java server is HTTP/database ready", flush=True)
        for label, check in checks:
            print(f"RUN: {label}", flush=True)
            prefix = os.environ.get("EVIDENCE_PREFIX", "dashboard")
            subprocess.run([sys.executable, str(check)], cwd=ROOT,
                           env={**env, "EVIDENCE_PREFIX": f"{prefix}-{label}"}, check=True)
        print("PASS: all dashboard acceptance checks executed", flush=True)
    finally:
        if server.poll() is None:
            server.terminate()
            try:
                server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()
        print("Stopped the isolated Java test process; persistent development data preserved", flush=True)
