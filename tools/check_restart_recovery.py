from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4
import json
import os
import subprocess
import sys
from urllib.parse import urlparse

import requests

root = Path(__file__).resolve().parent.parent
url = os.environ.get('DB_URL', '')
assert url.startswith('jdbc:mysql://') and urlparse(url[5:]).path == '/online_exam_e2e', (
    'Restart checks require the disposable online_exam_e2e database'
)
fixture_path = root / '.local' / 'p08-04-restart-fixtures.json'
password = (root / '.local' / 'demo-password.txt').read_text(encoding='utf-8').strip()
settings = {}
for line in (root / '.env').read_text(encoding='utf-8').splitlines():
    if '=' in line and not line.startswith('#'):
        key, value = line.split('=', 1)
        settings[key] = value
mysql_env = os.environ.copy()
mysql_env['MYSQL_PWD'] = settings['MYSQL_PASSWORD']
port = int(os.environ.get('E2E_PORT', '8080'))
assert 1024 <= port <= 65535, 'Invalid E2E_PORT'
base = f'http://127.0.0.1:{port}'


def db(sql):
    return subprocess.run([
        'docker', 'exec', '-e', 'MYSQL_PWD', 'online-exam-db-1',
        'mysql', '-u', 'online_exam', 'online_exam_e2e', '--batch', '--skip-column-names',
        '-e', sql
    ], env=mysql_env, capture_output=True, text=True, check=True, timeout=10).stdout.strip()


def csrf(session):
    token = session.get(base + '/api/csrf', timeout=10).json()
    return {token['headerName']: token['token']}


def login(username):
    session = requests.Session()
    response = session.post(base + '/login', data={'username': username, 'password': password},
                            headers=csrf(session), timeout=10)
    assert response.status_code == 204, response.status_code
    return session


def prepare():
    author = login('demo_instructor1')
    now = datetime.now(timezone.utc)
    created = author.post(base + '/api/exams', json={
        'title': 'Restart journey ' + uuid4().hex[:8],
        'description': 'Two persisted attempts across a process restart.',
        'durationMinutes': 30,
        'opensAt': (now - timedelta(minutes=5)).isoformat(),
        'closesAt': (now + timedelta(hours=1)).isoformat()
    }, headers=csrf(author), timeout=10)
    assert created.status_code == 201
    exam_id = created.json()['id']
    question = author.post(base + f'/api/exams/{exam_id}/questions', json={
        'text': 'Choose the saved answer', 'marks': 2, 'displayOrder': 0,
        'options': [
            {'text': 'Correct', 'correct': True, 'displayOrder': 0},
            {'text': 'Wrong', 'correct': False, 'displayOrder': 1}
        ]
    }, headers=csrf(author), timeout=10)
    assert question.status_code == 201
    question_id = question.json()['id']
    correct_id = question.json()['options'][0]['id']
    wrong_id = question.json()['options'][1]['id']
    assert author.post(base + f'/api/exams/{exam_id}/publish',
                       headers=csrf(author), timeout=10).status_code == 200

    fixture = {'question_id': question_id, 'correct_id': correct_id, 'wrong_id': wrong_id}
    for role, username, selected in (
        ('overdue', 'demo_student1', correct_id),
        ('live', 'demo_student2', wrong_id)
    ):
        student = login(username)
        started = student.post(base + f'/api/exams/{exam_id}/attempts',
                               headers=csrf(student), timeout=10)
        assert started.status_code in (200, 201), started.status_code
        attempt = started.json()
        saved = student.put(base + f'/api/attempts/{attempt["id"]}/answers/{question_id}',
                            json={'selectedOptionId': selected, 'expectedRevision': 0},
                            headers=csrf(student), timeout=10)
        assert saved.status_code == 200 and saved.json()['revision'] == 1
        fixture[role] = {'attempt_id': attempt['id'], 'deadline_at': attempt['deadlineAt']}
        student.close()
    fixture_path.write_text(json.dumps(fixture), encoding='utf-8')
    print('PREPARED: two MySQL attempts have saved answers and original deadlines')


def expire():
    fixture = json.loads(fixture_path.read_text(encoding='utf-8'))
    attempt_id = int(fixture['overdue']['attempt_id'])
    db('UPDATE attempts SET started_at = UTC_TIMESTAMP(6) - INTERVAL 3 MINUTE, '
       'deadline_at = UTC_TIMESTAMP(6) - INTERVAL 5 SECOND '
       f'WHERE id = {attempt_id} AND status = \'IN_PROGRESS\'')
    fixture['overdue']['controlled_deadline'] = db(
        f'SELECT deadline_at FROM attempts WHERE id = {attempt_id}')
    assert db(f'SELECT status FROM attempts WHERE id = {attempt_id}') == 'IN_PROGRESS'
    fixture_path.write_text(json.dumps(fixture), encoding='utf-8')
    print('CONTROLLED_EXPIRY: overdue row remains unfinalized while the application is stopped')


def verify():
    fixture = json.loads(fixture_path.read_text(encoding='utf-8'))
    overdue_id = int(fixture['overdue']['attempt_id'])
    live_id = int(fixture['live']['attempt_id'])
    assert db(f'SELECT status, score, total_marks FROM attempts WHERE id = {overdue_id}') \
        == 'TIMED_OUT\t2\t2'
    assert db(f'SELECT deadline_at FROM attempts WHERE id = {overdue_id}') \
        == fixture['overdue']['controlled_deadline']
    assert db(f'SELECT status FROM attempts WHERE id = {live_id}') == 'IN_PROGRESS'
    assert db(f'SELECT revision, selected_option_id FROM answers WHERE attempt_id = {live_id}') \
        == f'1\t{fixture["wrong_id"]}'

    live_student = login('demo_student2')
    live_response = live_student.get(base + f'/api/attempts/{live_id}', timeout=10)
    assert live_response.status_code == 200
    live = live_response.json()
    assert live['status'] == 'IN_PROGRESS'
    assert live['deadlineAt'] == fixture['live']['deadline_at']
    assert live['questions'][0]['selectedOptionId'] == fixture['wrong_id']
    assert live['questions'][0]['revision'] == 1
    overdue_student = login('demo_student1')
    result_response = overdue_student.get(base + f'/api/attempts/{overdue_id}/result', timeout=10)
    assert result_response.status_code == 200
    result = result_response.json()
    assert (result['status'], result['score'], result['totalMarks']) == ('TIMED_OUT', 2, 2)
    print('PASS: actual process restart finalized the overdue saved attempt, preserved both deadlines and answers, and resumed the live saved attempt after fresh login')


{'prepare': prepare, 'expire': expire, 'verify': verify}[sys.argv[1]]()
