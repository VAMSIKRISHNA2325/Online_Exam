from pathlib import Path
from xml.etree import ElementTree
import argparse
import json
import re

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument('--maven-log', default='evidence/p09-01-full-mysql-it.log')
parser.add_argument('--playwright-list', default='evidence/p09-01-playwright-list.log')
parser.add_argument('--playwright-run', default='evidence/p09-01-playwright-smoke-passing.log')
inputs = parser.parse_args()


def text(path):
    content = path.read_bytes()
    if content.startswith(b'\xff\xfe') or content[:200].count(b'\x00') > 20:
        return content.decode('utf-16', errors='ignore')
    return content.decode('utf-8')


def suite(directory):
    reports = list((root / 'target' / directory).glob('TEST-*.xml'))
    assert reports, f'No XML reports in {directory}'
    totals = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    for report in reports:
        summary = ElementTree.parse(report).getroot()
        for field in totals:
            totals[field] += int(summary.attrib.get(field, 0))
    assert totals['tests'] > 0 and totals['failures'] == totals['errors'] == totals['skipped'] == 0
    return totals['tests']


unit_count = suite('surefire-reports')
mysql_count = suite('failsafe-reports')
maven_log = text(root / inputs.maven_log)
assert 'BUILD SUCCESS' in maven_log
assert f'Tests run: {mysql_count}, Failures: 0, Errors: 0, Skipped: 0' in maven_log
pom = text(root / 'pom.xml')
assert pom.count('<failIfNoTests>true</failIfNoTests>') >= 2
assert '<include>**/*IT.java</include>' in pom and '<id>mysql-it</id>' in pom

listed = text(root / inputs.playwright_list)
ran = text(root / inputs.playwright_run)
selected = re.search(r'Total: (\d+) tests? in \d+ files?', listed)
started = re.search(r'Running (\d+) tests? using', ran)
passed = re.search(r'(\d+) passed \(', ran)
assert selected and started and passed
browser_count = int(selected.group(1))
assert browser_count > 0 and browser_count == int(started.group(1)) == int(passed.group(1))
assert not re.search(r'\b\d+ (?:failed|skipped|flaky)\b', ran)
assert (root / 'e2e' / 'playwright-report' / 'index.html').is_file()
package = json.loads(text(root / 'e2e' / 'package.json'))
lock = json.loads(text(root / 'e2e' / 'package-lock.json'))
assert package['scripts']['test:e2e'] == 'playwright test'
assert package['devDependencies']['@playwright/test'] == '1.63.0'
assert lock['packages']['node_modules/@playwright/test']['version'] == '1.63.0'
assert 'online_exam_e2e' in text(root / 'e2e' / 'playwright.config.js')

print(f'PASS: Surefire {unit_count}, real-MySQL Failsafe {mysql_count}, Playwright {browser_count}; '
      'all selected tests executed with zero failures/errors/skips and the isolated browser report exists')
