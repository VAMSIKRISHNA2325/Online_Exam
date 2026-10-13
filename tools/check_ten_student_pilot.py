from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path
from statistics import median
from threading import Barrier
from uuid import uuid4
import math
import os
import subprocess
import time

import requests

root = Path(__file__).resolve().parent.parent
password = (root / '.local' / 'demo-password.txt').read_text(encoding='utf-8').strip()
settings = {}
for line in (root / '.env').read_text(encoding='utf-8').splitlines():
    if '=' in line and not line.startswith('#'):
        key, value = line.split('=', 1)
        settings[key] = value
mysql_env = os.environ.copy()
mysql_env['MYSQL_PWD'] = settings['MYSQL_PASSWORD']
base = 'http://127.0.0.1:8080'
marker = uuid4().hex[:10]
barrier = Barrier(10)


def db(sql):
    return subprocess.run([
        'docker', 'exec', '-e', 'MYSQL_PWD', 'online-exam-db-1',
        'mysql', '-u', 'online_exam', 'online_exam_e2e', '--batch', '--skip-column-names',
        '-e', sql
    ], env=mysql_env, capture_output=True, text=True, check=True, timeout=10).stdout.strip()


def csrf(session):
    response = session.get(base + '/api/csrf', timeout=10)
    assert response.status_code == 200
    token = response.json()
    return {token['headerName']: token['token']}


def login(username):
    session = requests.Session()
    signed_in = session.post(base + '/login', data={
        'username': username, 'password': password
    }, headers=csrf(session), timeout=10)
    assert signed_in.status_code == 204, (username, signed_in.status_code)
    return session, csrf(session)


teacher, teacher_token = login('demo_instructor1')
now = datetime.now(timezone.utc)
created = teacher.post(base + '/api/exams', json={
    'title': 'Ten-student pilot ' + marker,
    'description': 'Two-question concurrent MySQL pilot.',
    'durationMinutes': 30,
    'opensAt': (now - timedelta(minutes=5)).isoformat(),
    'closesAt': (now + timedelta(hours=2)).isoformat()
}, headers=teacher_token, timeout=10)
assert created.status_code == 201
exam_id = created.json()['id']
paper = []
for index, (text, correct, wrong, marks) in enumerate((
    ('Two plus two?', 'Four', 'Five', 2),
    ('What color is the sky?', 'Blue', 'Red', 3)
)):
    added = teacher.post(base + f'/api/exams/{exam_id}/questions', json={
        'text': text, 'marks': marks, 'displayOrder': index,
        'options': [
            {'text': correct, 'correct': True, 'displayOrder': 0},
            {'text': wrong, 'correct': False, 'displayOrder': 1}
        ]
    }, headers=teacher_token, timeout=10)
    assert added.status_code == 201
    item = added.json()
    paper.append((item['id'], item['options'][0]['id'], item['options'][1]['id'], marks))
assert teacher.post(base + f'/api/exams/{exam_id}/publish',
                    headers=teacher_token, timeout=10).status_code == 200

# Disposable e2e fixtures copy the already encoded demo hash inside MySQL;
# no plaintext or special pilot authentication path is introduced.
usernames = [f'pilot_{marker}_{index:02d}' for index in range(10)]
for index, username in enumerate(usernames):
    db("INSERT INTO users(username, display_name, password_hash, role, enabled, created_at) "
       f"SELECT '{username}', 'Pilot Student {index:02d}', password_hash, 'STUDENT', 1, "
       "UTC_TIMESTAMP(6) FROM users WHERE username = 'demo_student1'")
assert int(db("SELECT COUNT(*) FROM users WHERE username LIKE 'pilot_" + marker + "_%'")) == 10


def student_run(index):
    username = usernames[index]
    session, token = login(username)
    barrier.wait(timeout=30)
    started_clock = time.perf_counter()
    started = session.post(base + f'/api/exams/{exam_id}/attempts',
                           headers=token, timeout=10)
    assert started.status_code == 200, (username, started.status_code)
    attempt = started.json()
    attempt_id = attempt['id']
    selections = {}
    expected_score = 0
    for question_index, (question_id, correct_id, wrong_id, marks) in enumerate(paper):
        choose_correct = index % (question_index + 2) == 0
        option_id = correct_id if choose_correct else wrong_id
        expected_score += marks if choose_correct else 0
        saved = session.put(base + f'/api/attempts/{attempt_id}/answers/{question_id}',
                            json={'selectedOptionId': option_id, 'expectedRevision': 0},
                            headers=token, timeout=10)
        assert saved.status_code == 200, (username, saved.status_code)
        assert saved.json()['revision'] == 1
        selections[question_id] = option_id
    resumed = session.get(base + f'/api/attempts/{attempt_id}', timeout=10)
    assert resumed.status_code == 200
    assert resumed.json()['deadlineAt'] == attempt['deadlineAt']
    for question in resumed.json()['questions']:
        assert question['selectedOptionId'] == selections[question['id']]
        assert question['revision'] == 1
    submitted = session.post(base + f'/api/attempts/{attempt_id}/submit',
                             headers=token, timeout=10)
    assert submitted.status_code == 200, (username, submitted.status_code)
    result = submitted.json()
    assert (result['status'], result['score'], result['totalMarks']) \
        == ('SUBMITTED', expected_score, 5)
    duration = time.perf_counter() - started_clock
    session.close()
    return {'username': username, 'attempt_id': attempt_id,
            'deadline_at': attempt['deadlineAt'], 'score': expected_score,
            'answers': selections, 'seconds': duration}


wall_started = time.perf_counter()
with ThreadPoolExecutor(max_workers=10) as pool:
    futures = [pool.submit(student_run, index) for index in range(10)]
    results = [future.result(timeout=60) for future in futures]
wall_seconds = time.perf_counter() - wall_started
assert len({row['attempt_id'] for row in results}) == 10

for row in results:
    attempt_id = row['attempt_id']
    stored = db('SELECT u.username, a.status, a.score, a.total_marks, '
                'a.finalized_at IS NOT NULL FROM attempts a JOIN users u ON u.id=a.student_id '
                f'WHERE a.id={attempt_id}')
    assert stored == f"{row['username']}\tSUBMITTED\t{row['score']}\t5\t1", stored
    answer_rows = db('SELECT question_id, selected_option_id, revision FROM answers '
                     f'WHERE attempt_id={attempt_id} ORDER BY question_id').splitlines()
    assert len(answer_rows) == 2
    for answer_row in answer_rows:
        question_id, option_id, revision = (int(value) for value in answer_row.split('\t'))
        assert option_id == row['answers'][question_id] and revision == 1

owner_results = teacher.get(base + f'/api/instructor/exams/{exam_id}/results', timeout=10)
assert owner_results.status_code == 200
assert len(owner_results.json()) == 10
assert {entry['username']: entry['result']['score'] for entry in owner_results.json()} \
    == {row['username']: row['score'] for row in results}
teacher.close()

durations = sorted(row['seconds'] for row in results)
p95 = durations[math.ceil(0.95 * len(durations)) - 1]
print(f'PASS: 10 distinct students started, saved 20 acknowledged answers, resumed and '
      f'submitted 10 independently scored MySQL attempts; owner roster and every answer/revision '
      f'reconciled; local concurrent run wall={wall_seconds:.2f}s, p50={median(durations):.2f}s, '
      f'p95={p95:.2f}s, max={durations[-1]:.2f}s')
