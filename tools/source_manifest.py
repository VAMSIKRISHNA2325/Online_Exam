"""Print a deterministic release source SHA-256 manifest without private files."""

import hashlib
from pathlib import Path

root = Path(__file__).resolve().parent.parent
paths = [*root.joinpath("src").rglob("*"), *root.joinpath("e2e", "tests").rglob("*"),
         root / "e2e/package.json", root / "e2e/package-lock.json", root / "e2e/playwright.config.js",
         root / "pom.xml", root / "compose.yaml", root / ".env.example", root / "README.md",
         root / "ARCHITECTURE.md", *root.joinpath("deploy").rglob("*")]
paths = sorted({p for p in paths if p.is_file()}, key=lambda p: str(p.relative_to(root)))
whole = hashlib.sha256()
for path in paths:
    relative = path.relative_to(root).as_posix()
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    whole.update((relative + " " + digest + "\n").encode("utf-8"))
    print(f"{digest}  {relative}")
print(f"MANIFEST_SHA256 {whole.hexdigest()} FILES {len(paths)}")
