from pathlib import Path
import re

root = Path(__file__).resolve().parent.parent
settings = {}
for line in (root / '.env').read_text(encoding='utf-8').splitlines():
    if '=' in line and not line.startswith('#'):
        key, value = line.split('=', 1)
        settings[key] = value
secrets = [settings.get('MYSQL_PASSWORD'), settings.get('MYSQL_ROOT_PASSWORD')]
for filename in ('demo-password.txt', 'local-student-password.txt'):
    path = root / '.local' / filename
    if path.exists():
        secrets.append(path.read_text(encoding='utf-8').strip())
secrets = [value for value in secrets if value and len(value) >= 12]
assert secrets, 'No generated local secrets available for the audit'

# Explicit roster credentials are eight-digit roll numbers, so include them
# separately from the longer generated development/demo passwords above.
roster_path = root / '.local' / 'student-roster.tsv'
if roster_path.exists():
    for line in roster_path.read_text(encoding='utf-8').splitlines():
        if not line.strip():
            continue
        fields = line.split('\t')
        assert len(fields) == 2 and re.fullmatch(r'[0-9]{8}', fields[1]), 'Invalid private roster format'
        secrets.append(fields[1])
secrets = list(set(secrets))


def readable_text(path):
    content = path.read_bytes()
    if content.startswith(b'\xff\xfe') or content[:200].count(b'\x00') > 20:
        return content.decode('utf-16', errors='ignore')
    return content.decode('utf-8')

excluded = {'.local', 'target', 'evidence', '.git', 'node_modules', 'test-results', 'playwright-report'}
audited = 0
violations = []
for path in root.rglob('*'):
    if not path.is_file() or any(part in excluded for part in path.relative_to(root).parts):
        continue
    if path.name == '.env' or path.stat().st_size > 2_000_000:
        continue
    try:
        content = readable_text(path)
    except UnicodeDecodeError:
        continue
    audited += 1
    if any(secret in content for secret in secrets):
        violations.append(path.relative_to(root).as_posix())

runtime_paths = sorted(set((root / '.local').glob('*app-start.log'))
                       | set((root / '.local').glob('p08-04-restart-*.log')))
for path in runtime_paths:
    runtime = readable_text(path)
    if any(secret in runtime for secret in secrets):
        violations.append('runtime startup log')
    if 'JSESSIONID=' in runtime or 'X-CSRF-TOKEN:' in runtime:
        violations.append('runtime startup log contains a raw session or CSRF value')
evidence_files = 0
for path in (root / 'evidence').rglob('*'):
    if not path.is_file():
        continue
    try:
        content = readable_text(path)
    except UnicodeDecodeError:
        continue
    evidence_files += 1
    if any(secret in content for secret in secrets):
        violations.append('evidence contains a generated local secret')
    if re.search(r'JSESSIONID=[A-Za-z0-9_-]{8,}|X-CSRF-TOKEN:?["=\s]+[A-Za-z0-9_-]{16,}|_csrf=\[[A-Za-z0-9_-]{16,}', content):
        violations.append('evidence contains a raw session or CSRF value')
assert not violations, f'Secret audit failed in {len(violations)} checked locations'
print(f'PASS: {audited} repository text files, {evidence_files} evidence files and {len(runtime_paths)} application logs contain no generated local secrets or raw session/CSRF values')
