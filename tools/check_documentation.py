"""Check release guide links, command names, and documented API paths."""

from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parent.parent
files = [root / "README.md", root / "START_HERE.md", root / "ARCHITECTURE.md",
         *sorted((root / "docs").glob("*.md"))]
missing = []
links = 0
for file in files:
    source = file.read_text(encoding="utf-8")
    for raw in re.findall(r"(?<!!)\[[^]]+\]\(([^)]+)\)", source):
        if raw.startswith(("http://", "https://", "mailto:")):
            continue
        target = raw.split("#", 1)[0]
        if target and not (file.parent / target).is_file():
            missing.append(f"{file.relative_to(root)} -> {target}")
        links += 1

readme = (root / "README.md").read_text(encoding="utf-8")
for command in ("mvnw.cmd -B -ntp clean package", "mvnw.cmd -B -ntp -Pmysql-it verify",
                "npm.cmd --prefix e2e ci", "npm.cmd --prefix e2e run install:chromium",
                "npm.cmd --prefix e2e run test:e2e", "validate_trackers.mjs --require-complete"):
    if command not in readme:
        missing.append(f"README lacks command: {command}")

api = (root / "docs/API.md").read_text(encoding="utf-8")
java = "\n".join(p.read_text(encoding="utf-8") for p in
                 (root / "src/main/java/com/onlineexam/controller").glob("*.java"))
for path in re.findall(r"`(?:GET|POST|PUT|DELETE) (/api/[^` ]+)`", api):
    if path not in java:
        missing.append(f"API route absent from controller source: {path}")

if missing:
    print("FAIL: " + "; ".join(missing), file=sys.stderr)
    sys.exit(1)
print(f"PASS: {len(files)} release Markdown files, {links} local links, six documented command contracts "
      "and documented API routes resolve to source; no missing reference.")
