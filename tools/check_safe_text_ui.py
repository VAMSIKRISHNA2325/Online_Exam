from datetime import datetime, timedelta, timezone
from uuid import uuid4
import re

import requests
from playwright.sync_api import expect, sync_playwright
from browser_support import base, browser as browser_path, evidence, password

marker = uuid4().hex[:8]
malicious = f'<img src=x onerror="window.examInjected=true"> {marker} \' OR 1=1 --'
description = f'<script>window.examInjected=true</script> {marker}'
errors = []


def csrf(session):
    response = session.get(base + '/api/csrf', timeout=10)
    assert response.status_code == 200
    token = response.json()
    return {token['headerName']: token['token']}


unknown = requests.Session()
denied = unknown.post(base + '/login', data={
    'username': "' OR 1=1 --", 'password': "' OR 1=1 --"
}, headers=csrf(unknown), timeout=10)
assert denied.status_code == 401 and denied.json()['code'] == 'BAD_CREDENTIALS'
assert password not in denied.text
oversized = unknown.post(base + '/login', data={
    'username': 'a' * 81, 'password': 'x'
}, headers=csrf(unknown), timeout=10)
assert oversized.status_code == 400 and oversized.json()['code'] == 'BAD_REQUEST'

author = requests.Session()
assert author.post(base + '/login', data={
    'username': 'demo_instructor1', 'password': password
}, headers=csrf(author), timeout=10).status_code == 204
now = datetime.now(timezone.utc)
created = author.post(base + '/api/exams', json={
    'title': malicious, 'description': description, 'durationMinutes': 30,
    'opensAt': (now - timedelta(minutes=5)).isoformat(),
    'closesAt': (now + timedelta(hours=1)).isoformat()
}, headers=csrf(author), timeout=10)
assert created.status_code == 201, created.status_code
exam_id = created.json()['id']
assert created.json()['title'] == malicious

added = author.post(base + f'/api/exams/{exam_id}/questions', json={
    'text': malicious, 'marks': 2, 'displayOrder': 0,
    'options': [
        {'text': malicious, 'correct': True, 'displayOrder': 0},
        {'text': 'Other', 'correct': False, 'displayOrder': 1}
    ]
}, headers=csrf(author), timeout=10)
assert added.status_code == 201, added.status_code
assert added.json()['text'] == malicious
assert author.post(base + f'/api/exams/{exam_id}/publish',
                   headers=csrf(author), timeout=10).status_code == 200

bad = author.post(base + '/api/exams', json={'title': 'Invalid'},
                  headers=csrf(author), timeout=10)
assert bad.status_code == 400 and bad.json() == {
    'code': 'BAD_REQUEST', 'message': 'Check the submitted data.'
}
assert password not in bad.text and 'SQLException' not in bad.text


def assert_plain_text(page):
    assert page.locator('img[src="x"]').count() == 0
    assert page.evaluate('window.examInjected === undefined')


with sync_playwright() as playwright:
    browser = playwright.chromium.launch(headless=True, executable_path=browser_path)
    instructor = browser.new_context(viewport={'width': 1440, 'height': 900})
    teacher = instructor.new_page()
    teacher.on('pageerror', lambda error: errors.append(str(error)))
    teacher.goto(base + '/login.html')
    teacher.locator('#username').fill('demo_instructor1')
    teacher.locator('#password').fill(password)
    teacher.locator('#sign-in').click()
    expect(teacher).to_have_url(base + '/home.html')
    teacher.goto(base + '/instructor.html')
    teacher.locator('.exam-list-item').filter(has_text=marker).click()
    expect(teacher.locator('#editor-title')).to_have_text(malicious)
    expect(teacher.locator('.question-card h3')).to_have_text(malicious)
    assert_plain_text(teacher)
    assert "script-src 'self'" in teacher.request.get(base + '/instructor.html').headers.get(
        'content-security-policy', '')

    student = browser.new_context(viewport={'width': 1440, 'height': 900})
    page = student.new_page()
    page.on('pageerror', lambda error: errors.append(str(error)))
    page.goto(base + '/login.html')
    page.locator('#username').fill('demo_student2')
    page.locator('#password').fill(password)
    page.locator('#sign-in').click()
    expect(page).to_have_url(base + '/home.html')
    page.goto(base + '/student.html')
    card = page.locator('.catalog-card').filter(has_text=marker)
    expect(card).to_have_count(1)
    expect(card.locator('h3')).to_have_text(malicious)
    assert_plain_text(page)
    card.get_by_role('button', name='Read instructions').click()
    page.locator('#dialog-start').click()
    expect(page).to_have_url(re.compile(r'/exam\.html\?id=\d+$'))
    expect(page.locator('#exam-title')).to_have_text(malicious)
    expect(page.locator('#question-title')).to_have_text(malicious)
    expect(page.locator('#answer-options .answer-option span').first).to_have_text(malicious)
    assert_plain_text(page)
    page.screenshot(path=evidence('safe-text-student', 'p08-02'), full_page=True)
    assert not errors, errors
    page.goto(base + '/home.html')
    page.locator('#sign-out').click()
    expect(page).to_have_url(base + '/login.html')
    browser.close()

print('PASS: SQL-like login was denied; malicious exam text persisted as data and rendered literally on instructor/student screens; CSP, bounded input and safe validation errors passed')
