"""Shared settings for checks against the disposable browser database."""

import os
import subprocess
from pathlib import Path
from urllib.parse import urlparse

root = Path(__file__).resolve().parent.parent
url = os.environ.get("DB_URL", "")
assert url.startswith("jdbc:mysql://") and urlparse(url[5:]).path == "/online_exam_e2e", (
    "Browser checks require the disposable online_exam_e2e database"
)
port = int(os.environ.get("E2E_PORT", "8080"))
assert 1024 <= port <= 65535, "Invalid E2E_PORT"
base = f"http://127.0.0.1:{port}"
password = (root / ".local/demo-password.txt").read_text(encoding="utf-8").strip()
browser = os.environ.get("E2E_BROWSER") or subprocess.check_output(
    ["node", "-e", "console.log(require('./e2e/node_modules/playwright').chromium.executablePath())"],
    cwd=root, text=True,
).strip()


def evidence(name, prefix):
    prefix = os.environ.get("EVIDENCE_PREFIX", prefix)
    return str(root / "evidence" / f"{prefix}-{name}.png")
