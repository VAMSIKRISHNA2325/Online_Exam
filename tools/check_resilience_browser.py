from datetime import datetime, timedelta, timezone
from uuid import uuid4
import os
import re
import subprocess
import time

import requests
from playwright.sync_api import expect, sync_playwright
from browser_support import base, browser as browser_path, evidence, password, root

settings = {}
for line in (root / '.env').read_text(encoding='utf-8').splitlines():
    if '=' in line and not line.startswith('#'):
        key, value = line.split('=', 1)
        settings[key] = value
mysql_env = os.environ.copy()
mysql_env['MYSQL_PWD'] = settings['MYSQL_PASSWORD']
title = 'Resilience journey ' + uuid4().hex[:8]
errors = []


def db(sql):
    result = subprocess.run([
        'docker', 'exec', '-e', 'MYSQL_PWD', 'online-exam-db-1',
        'mysql', '-u', 'online_exam', 'online_exam_e2e', '--batch', '--skip-column-names',
        '-e', sql
    ], env=mysql_env, capture_output=True, text=True, check=True, timeout=10)
    return result.stdout.strip()


def expire(attempt_id):
    assert isinstance(attempt_id, int) and attempt_id > 0
    db('UPDATE attempts SET started_at = UTC_TIMESTAMP(6) - INTERVAL 3 MINUTE, '
       'deadline_at = UTC_TIMESTAMP(6) - INTERVAL 5 SECOND '
       f'WHERE id = {attempt_id} AND status = \'IN_PROGRESS\'')


def csrf(session):
    token = session.get(base + '/api/csrf', timeout=10).json()
    return {token['headerName']: token['token']}


author = requests.Session()
assert author.post(base + '/login', data={
    'username': 'demo_instructor1', 'password': password
}, headers=csrf(author), timeout=10).status_code == 204
now = datetime.now(timezone.utc)
created = author.post(base + '/api/exams', json={
    'title': title, 'description': 'One saved answer and controlled server expiry.',
    'durationMinutes': 1, 'opensAt': (now - timedelta(minutes=5)).isoformat(),
    'closesAt': (now + timedelta(hours=1)).isoformat()
}, headers=csrf(author), timeout=10)
assert created.status_code == 201
exam_id = created.json()['id']
question = author.post(base + f'/api/exams/{exam_id}/questions', json={
    'text': 'Which option is correct?', 'marks': 2, 'displayOrder': 0,
    'options': [
        {'text': 'Correct', 'correct': True, 'displayOrder': 0},
        {'text': 'Wrong', 'correct': False, 'displayOrder': 1}
    ]
}, headers=csrf(author), timeout=10)
assert question.status_code == 201
assert author.post(base + f'/api/exams/{exam_id}/publish',
                   headers=csrf(author), timeout=10).status_code == 200


def start_attempt(browser, username):
    context = browser.new_context(viewport={'width': 1440, 'height': 900})
    page = context.new_page()
    page.on('pageerror', lambda error: errors.append(str(error)))
    page.goto(base + '/login.html')
    page.locator('#username').fill(username)
    page.locator('#password').fill(password)
    page.locator('#sign-in').click()
    expect(page).to_have_url(base + '/home.html')
    page.goto(base + '/student.html')
    card = page.locator('.catalog-card').filter(has_text=title)
    card.get_by_role('button', name='Read instructions').click()
    page.locator('#dialog-start').click()
    expect(page).to_have_url(re.compile(r'/exam\.html\?id=\d+$'))
    return context, page, int(page.url.split('id=')[1])


with sync_playwright() as playwright:
    browser = playwright.chromium.launch(headless=True, executable_path=browser_path)
    context, page, attempt_id = start_attempt(browser, 'demo_student3')
    cdp = context.new_cdp_session(page)
    cdp.send('Network.enable')
    cdp.send('Network.emulateNetworkConditions', {
        'offline': False, 'latency': 2500,
        'downloadThroughput': -1, 'uploadThroughput': -1
    })
    page.locator('#answer-options input[type="radio"]').first.check()
    expect(page.locator('#save-state')).to_contain_text('Saving')
    expect(page.locator('#submit-attempt')).to_be_disabled()
    cdp.send('Network.emulateNetworkConditions', {
        'offline': False, 'latency': 0,
        'downloadThroughput': -1, 'uploadThroughput': -1
    })
    expect(page.locator('#save-state')).to_contain_text('Saved', timeout=15000)

    page.route('**/api/attempts/*/answers/*', lambda route: route.abort('failed'))
    page.locator('#answer-options input[type="radio"]').nth(1).check()
    expect(page.locator('#save-state')).to_contain_text('Not saved')
    expect(page.locator('#submit-attempt')).to_be_disabled()
    context.set_offline(True)
    expect(page.locator('#connection-notice')).to_contain_text('offline')
    page.screenshot(path=evidence('unsaved-browser', 'p08-04'), full_page=True)
    context.set_offline(False)
    page.unroute('**/api/attempts/*/answers/*')
    page.locator('#retry-save').click()
    expect(page.locator('#save-state')).to_contain_text('Saved')
    assert context.request.get(base + f'/api/attempts/{attempt_id}').json()['questions'][0]['revision'] == 2

    expire(attempt_id)
    page.locator('#answer-options input[type="radio"]').first.check()
    expect(page.locator('#result-score')).to_have_text('0 / 2', timeout=15000)
    assert context.request.get(base + f'/api/attempts/{attempt_id}/result').json()['status'] == 'TIMED_OUT'
    assert db(f'SELECT revision FROM answers WHERE attempt_id = {attempt_id}') == '2'
    page.screenshot(path=evidence('expired-browser', 'p08-04'), full_page=True)
    context.close()

    closed_context, closed_page, closed_attempt = start_attempt(browser, 'demo_student1')
    closed_page.locator('#answer-options input[type="radio"]').first.check()
    expect(closed_page.locator('#save-state')).to_contain_text('Saved')
    closed_context.close()
    expire(closed_attempt)
    terminal = ''
    for _ in range(45):
        terminal = db(f'SELECT status, score, total_marks FROM attempts WHERE id = {closed_attempt}')
        if terminal.startswith('TIMED_OUT'):
            break
        time.sleep(1)
    assert terminal == 'TIMED_OUT\t2\t2', terminal
    assert db(f'SELECT revision FROM answers WHERE attempt_id = {closed_attempt}') == '1'
    assert not errors, errors
    browser.close()

print('PASS: pending save disabled submit; failed/offline save stayed unsaved; retry persisted revision 2; post-expiry write was rejected and score stayed 0/2; a closed browser attempt timed out from saved MySQL answers without a client callback')
