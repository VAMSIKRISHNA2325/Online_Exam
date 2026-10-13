from datetime import datetime, timedelta, timezone
from uuid import uuid4
import re
import requests
from playwright.sync_api import sync_playwright, expect
from browser_support import base, browser as browser_path, evidence, password

title = 'Keyboard offline ' + uuid4().hex[:8]
errors = []

author = requests.Session()
def csrf():
    token = author.get(base + '/api/csrf', timeout=10).json()
    return {token['headerName']: token['token']}

assert author.post(base + '/login', data={
    'username': 'demo_instructor1', 'password': password
}, headers=csrf(), timeout=10).status_code == 204
now = datetime.now(timezone.utc)
created = author.post(base + '/api/exams', json={
    'title': title, 'description': 'Keyboard and offline test paper.',
    'durationMinutes': 30,
    'opensAt': (now - timedelta(minutes=5)).isoformat(),
    'closesAt': (now + timedelta(hours=1)).isoformat()
}, headers=csrf(), timeout=10)
assert created.status_code == 201
exam_id = created.json()['id']
for index, (question, key, wrong, marks) in enumerate([
    ('What is one plus one?', 'Two', 'Three', 2),
    ('Which color is the sky?', 'Blue', 'Red', 3)
]):
    added = author.post(base + f'/api/exams/{exam_id}/questions', json={
        'text': question, 'marks': marks, 'displayOrder': index,
        'options': [
            {'text': key, 'correct': True, 'displayOrder': 0},
            {'text': wrong, 'correct': False, 'displayOrder': 1}
        ]
    }, headers=csrf(), timeout=10)
    assert added.status_code == 201
assert author.post(base + f'/api/exams/{exam_id}/publish', headers=csrf(), timeout=10).status_code == 200

with sync_playwright() as playwright:
    browser = playwright.chromium.launch(headless=True, executable_path=browser_path)
    context = browser.new_context(viewport={'width': 1440, 'height': 900})
    page = context.new_page()
    page.on('pageerror', lambda error: errors.append(str(error)))
    api_events = []
    page.on('request', lambda request: api_events.append('request ' + request.url.split('/api/')[-1]) if '/api/' in request.url else None)
    page.on('response', lambda response: api_events.append('response ' + str(response.status) + ' ' + response.url.split('/api/')[-1]) if '/api/' in response.url else None)
    page.on('requestfailed', lambda request: api_events.append('failed ' + request.url.split('/api/')[-1]) if '/api/' in request.url else None)
    page.goto(base + '/login.html')
    page.locator('#username').focus()
    page.keyboard.type('demo_student1')
    page.locator('#password').focus()
    page.keyboard.type(password)
    page.keyboard.press('Enter')
    expect(page).to_have_url(base + '/home.html')
    expect(page.locator('#student-link')).to_be_visible()
    page.locator('#student-link').focus()
    page.keyboard.press('Enter')
    expect(page).to_have_url(base + '/student.html')
    card = page.locator('.catalog-card').filter(has_text=title)
    card.get_by_role('button', name='Read instructions').focus()
    page.keyboard.press('Enter')
    expect(page.locator('#exam-dialog')).to_be_visible()
    page.locator('#dialog-start').focus()
    page.keyboard.press('Enter')
    expect(page).to_have_url(re.compile(r'/exam\.html\?id=\d+$'))
    expect(page.locator('#question-title')).to_have_text('What is one plus one?')

    first = page.locator('#answer-options input[type="radio"]').first
    first.focus()
    page.keyboard.press('Space')
    expect(first).to_be_checked()
    try:
        expect(page.locator('#save-state')).to_contain_text('Saved', timeout=18000)
    except Exception:
        print('PAGE_ERRORS', errors)
        print('API_EVENTS', api_events[-20:])
        print('SAVE_ERROR', page.locator('#save-error').inner_text())
        raise
    assert page.evaluate('document.activeElement?.value') == first.get_attribute('value')
    page.locator('#next-question').focus()
    page.keyboard.press('Enter')
    expect(page.locator('#question-title')).to_have_text('Which color is the sky?')
    page.locator('#question-navigation button').first.focus()
    page.keyboard.press('Enter')
    expect(page.locator('#question-title')).to_have_text('What is one plus one?')
    assert page.evaluate("document.activeElement?.closest('#question-navigation') !== null")

    page.set_viewport_size({'width': 360, 'height': 780})
    assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth')
    page.screenshot(path=evidence('keyboard-exam-mobile', 'p07-04'), full_page=True)
    page.locator('#next-question').focus()
    page.keyboard.press('Enter')
    context.set_offline(True)
    expect(page.locator('#connection-notice')).to_contain_text('offline')
    second_wrong = page.locator('#answer-options input[type="radio"]').nth(1)
    second_wrong.focus()
    page.keyboard.press('Space')
    expect(page.locator('#save-state')).to_contain_text('Not saved')
    expect(page.locator('#save-error')).to_contain_text('not saved')
    expect(page.locator('#submit-attempt')).to_be_disabled()
    assert page.locator('#exam-progress').inner_text().startswith('1 of 2')
    page.evaluate("window.dispatchEvent(new Event('focus'))")
    expect(second_wrong).to_be_checked()
    expect(page.locator('#save-state')).to_contain_text('Not saved')
    page.screenshot(path=evidence('offline-unsaved-mobile', 'p07-04'), full_page=True)

    context.set_offline(False)
    expect(page.locator('#connection-notice')).to_contain_text('Retry each unsaved')
    page.locator('#retry-save').focus()
    page.keyboard.press('Enter')
    expect(page.locator('#save-state')).to_contain_text('Saved')
    expect(page.locator('#submit-attempt')).to_be_enabled()
    expect(page.locator('#connection-notice')).to_be_hidden()
    page.locator('#submit-attempt').focus()
    page.keyboard.press('Enter')
    expect(page.locator('#submit-dialog')).to_be_visible()
    page.keyboard.press('Escape')
    expect(page.locator('#submit-dialog')).to_be_hidden()
    page.locator('#submit-attempt').focus()
    page.keyboard.press('Enter')
    page.locator('#confirm-submit').focus()
    page.keyboard.press('Enter')
    expect(page.locator('#result-score')).to_have_text('2 / 5')
    assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth')
    page.screenshot(path=evidence('keyboard-result-mobile', 'p07-04'), full_page=True)
    assert not errors, errors
    page.goto(base + '/home.html')
    page.locator('#sign-out').click()
    expect(page).to_have_url(base + '/login.html')
    browser.close()

print('PASS: keyboard-only login/exam/navigation/submit, focus retained after render, 360px layout, offline unsaved warning and disabled submit, reconnect/retry to acknowledged MySQL save, no page errors or overflow')
