const { test, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const password = process.env.DEMO_PASSWORD ||
  fs.readFileSync(path.join(root, '.local', 'demo-password.txt'), 'utf8').trim();

function localDateTime(offsetMinutes) {
  const date = new Date(Date.now() + offsetMinutes * 60_000);
  const pad = value => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    + `T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function monitor(page, issues, expectedFailures = { resultAbort: false }) {
  page.on('pageerror', error => issues.push(`page: ${error.message}`));
  page.on('console', message => {
    if (message.type() !== 'error') return;
    // Chromium reports handled 401/409 API responses as generic resource errors.
    // The response listener below checks that those statuses came only from
    // the expected current-user and deliberate invalid-publication requests.
    if (/^Failed to load resource: the server responded with a status of (401|409)\b/.test(message.text())) return;
    if (expectedFailures.resultAbort && message.text().includes('net::ERR_FAILED')) return;
    issues.push(`console: ${message.text()}`);
  });
  page.on('response', response => {
    const route = new URL(response.url()).pathname;
    const expected = (response.status() === 401 && route === '/api/me')
      || (response.status() === 409 && /^\/api\/exams\/\d+\/publish$/.test(route));
    if (response.status() >= 400 && !expected) {
      issues.push(`HTTP ${response.status()}: ${route}`);
    }
  });
  page.on('requestfailed', request => {
    if (expectedFailures.resultAbort
        && /\/api\/attempts\/\d+\/result$/.test(new URL(request.url()).pathname)) return;
    if (request.failure()?.errorText !== 'net::ERR_ABORTED') {
      issues.push(`network: ${request.url()}`);
    }
  });
}

async function signIn(page, username, togglePassword = false) {
  await page.goto('/login.html');
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  if (togglePassword) {
    await page.locator('#toggle-password').click();
    await expect(page.locator('#password')).toHaveAttribute('type', 'text');
    await page.locator('#toggle-password').click();
    await expect(page.locator('#password')).toHaveAttribute('type', 'password');
  }
  await page.locator('#sign-in').click();
  await expect(page).toHaveURL(/\/home\.html$/);
}

async function addQuestion(page, question, marks, correct, wrong) {
  await page.locator('#add-question').click();
  await expect(page.locator('#question-editor')).toBeVisible();
  await page.locator('#question-text').fill(question);
  await page.locator('#question-marks').fill(String(marks));
  const rows = page.locator('#options-list .option-row');
  await rows.nth(0).locator('input[type="text"]').fill(correct);
  await rows.nth(1).locator('input[type="text"]').fill(wrong);
  await rows.nth(0).locator('input[type="radio"]').check();
  await page.locator('#save-question').click();
  await expect(page.locator('#question-editor')).toBeHidden();
  await expect(page.locator('.question-card').filter({ hasText: question })).toBeVisible();
}

test('UI creates and publishes a paper, student saves and submits, instructor reviews MySQL result', async ({ browser }) => {
  test.setTimeout(120_000);
  const issues = [];
  const title = `Browser journey ${Date.now().toString(36)}`;
  const instructor = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const teacher = await instructor.newPage();
  monitor(teacher, issues);
  await signIn(teacher, 'demo_instructor1', true);
  await teacher.locator('#instructor-link').click();
  await expect(teacher).toHaveURL(/\/instructor\.html$/);
  await expect(teacher.locator('#desk-loading')).toBeHidden();

  await teacher.locator('#new-exam').click();
  await teacher.locator('#exam-title').fill(title);
  await teacher.locator('#exam-description').fill('Two questions, five marks, created in the browser.');
  await teacher.locator('#exam-duration').fill('30');
  await teacher.locator('#exam-opens').fill(localDateTime(-5));
  await teacher.locator('#exam-closes').fill(localDateTime(60));
  await teacher.locator('#save-exam').click();
  await expect(teacher.locator('#desk-status')).toContainText('Draft created');
  await expect(teacher.locator('.exam-list-item').filter({ hasText: title })).toHaveCount(1);

  await teacher.locator('#exam-description').fill('Edited before publication in the browser.');
  await teacher.locator('#save-exam').click();
  await expect(teacher.locator('#desk-status')).toContainText('Exam details saved');
  await teacher.locator('#publish-exam').click();
  await expect(teacher.locator('#desk-error')).toContainText('Add at least one');

  await addQuestion(teacher, 'First draft question?', 2, 'Two', 'Three');
  const firstCard = teacher.locator('.question-card').filter({ hasText: 'First draft question?' });
  await firstCard.getByRole('button', { name: 'Edit' }).click();
  await teacher.locator('#question-text').fill('What is one plus one?');
  await teacher.locator('#add-option').click();
  await expect(teacher.locator('#options-list .option-row')).toHaveCount(3);
  await teacher.locator('#options-list .option-row').nth(2).getByRole('button', { name: 'Remove' }).click();
  await expect(teacher.locator('#options-list .option-row')).toHaveCount(2);
  await teacher.locator('#save-question').click();
  await expect(teacher.locator('.question-card').filter({ hasText: 'What is one plus one?' })).toBeVisible();
  await addQuestion(teacher, 'Which color is the sky?', 3, 'Blue', 'Red');
  await teacher.locator('#add-question').click();
  await teacher.locator('#cancel-question').click();
  await expect(teacher.locator('#question-editor')).toBeHidden();
  await addQuestion(teacher, 'Temporary draft question?', 1, 'Keep', 'Remove');
  teacher.once('dialog', dialog => dialog.accept());
  await teacher.locator('.question-card').filter({ hasText: 'Temporary draft question?' })
    .getByRole('button', { name: 'Delete' }).click();
  await expect(teacher.locator('#question-count')).toHaveText('02');

  await teacher.locator('#publish-exam').click();
  await expect(teacher.locator('#exam-status')).toHaveText('PUBLISHED');
  await expect(teacher.locator('#recovery-section')).toBeVisible();
  await expect(teacher.locator('#results-section')).toBeVisible();
  await teacher.locator('#refresh-recovery').click();
  await teacher.locator('#refresh-results').click();
  await teacher.screenshot({ path: path.join(root, 'evidence', 'p09-02-ui-published-paper.png'), fullPage: true });

  const student = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await student.newPage();
  const expectedFailures = { resultAbort: false };
  monitor(page, issues, expectedFailures);
  await signIn(page, 'demo_student2');
  await page.locator('#student-link').click();
  await expect(page).toHaveURL(/\/student\.html$/);
  await page.locator('#refresh-catalog').click();
  const card = page.locator('.catalog-card').filter({ hasText: title });
  await expect(card).toHaveCount(1);
  await card.getByRole('button', { name: 'Read instructions' }).click();
  await expect(page.locator('#exam-dialog')).toBeVisible();
  await expect(page.locator('#dialog-marks')).toHaveText('5');
  await page.locator('#close-dialog').click();
  await expect(page.locator('#exam-dialog')).toBeHidden();
  await card.getByRole('button', { name: 'Read instructions' }).click();
  await page.locator('#dialog-cancel').click();
  await card.getByRole('button', { name: 'Read instructions' }).click();
  await page.locator('#dialog-start').click();
  await expect(page).toHaveURL(/\/exam\.html\?id=\d+$/);
  const attemptId = Number(new URL(page.url()).searchParams.get('id'));
  const before = await (await student.request.get(`/api/attempts/${attemptId}`)).json();
  const deadline = before.deadlineAt;
  await expect(page.locator('#question-title')).toHaveText('What is one plus one?');

  await page.locator('#answer-options input[type="radio"]').first().check();
  await expect(page.locator('#save-state')).toContainText('Saved');
  await page.locator('#clear-answer').click();
  await expect(page.locator('#save-state')).toContainText('Cleared');
  await page.locator('#answer-options input[type="radio"]').first().check();
  await expect(page.locator('#save-state')).toContainText('Saved');
  await page.locator('#next-question').click();
  await expect(page.locator('#question-title')).toHaveText('Which color is the sky?');
  await page.locator('#answer-options input[type="radio"]').nth(1).check();
  await expect(page.locator('#save-state')).toContainText('Saved');
  await page.locator('#question-navigation button').first().click();
  await expect(page.locator('#question-title')).toHaveText('What is one plus one?');
  await page.reload();
  await expect(page.locator('#save-state')).toContainText('Saved');
  const resumed = await (await student.request.get(`/api/attempts/${attemptId}`)).json();
  expect(resumed.deadlineAt).toBe(deadline);
  expect(resumed.questions[0].revision).toBe(3);
  await expect(page.locator('#exam-progress')).toContainText('2 of 2');

  await page.locator('#submit-attempt').click();
  await expect(page.locator('#submit-dialog')).toBeVisible();
  await page.locator('#cancel-submit').click();
  await expect(page.locator('#submit-dialog')).toBeHidden();
  await page.locator('#submit-attempt').click();
  await page.locator('#confirm-submit').click();
  await expect(page.locator('#result-score')).toHaveText('2 / 5');
  await expect(page.locator('#result-percentage')).toHaveText('40.00%');
  const result = await (await student.request.get(`/api/attempts/${attemptId}/result`)).json();
  expect([result.score, result.totalMarks, result.status]).toEqual([2, 5, 'SUBMITTED']);
  await page.screenshot({ path: path.join(root, 'evidence', 'p09-02-ui-student-result.png'), fullPage: true });

  expectedFailures.resultAbort = true;
  await page.route('**/api/attempts/*/result', route => route.abort('failed'));
  await page.reload();
  await expect(page.locator('#retry-result')).toBeVisible();
  await page.unroute('**/api/attempts/*/result');
  await page.locator('#retry-result').click();
  await expect(page.locator('#result-score')).toHaveText('2 / 5');
  expectedFailures.resultAbort = false;

  await page.goto('/student.html');
  await page.locator('.catalog-card').filter({ hasText: title })
    .getByRole('button', { name: /View result/ }).click();
  await expect(page.locator('#result-score')).toHaveText('2 / 5');
  await page.goto('/home.html');
  await page.locator('#sign-out').click();
  await expect(page).toHaveURL(/\/login\.html$/);

  await teacher.locator('#refresh-results').click();
  await expect(teacher.locator('#results-list .result-row').filter({ hasText: 'demo_student2' }))
    .toContainText('2 / 5');
  await teacher.screenshot({ path: path.join(root, 'evidence', 'p09-02-ui-instructor-review.png'), fullPage: true });
  await teacher.goto('/home.html');
  await teacher.locator('#sign-out').click();
  await expect(teacher).toHaveURL(/\/login\.html$/);

  expect(issues).toEqual([]);
  await student.close();
  await instructor.close();
});

test('instructor can create and delete a draft from the empty desk', async ({ page }) => {
  const issues = [];
  const title = `Disposable draft ${Date.now().toString(36)}`;
  monitor(page, issues);
  await signIn(page, 'demo_instructor2');
  await page.locator('#instructor-link').click();
  await expect(page.locator('#desk-loading')).toBeHidden();
  if (await page.locator('#desk-empty').isVisible()) {
    await page.locator('#empty-create').click();
  } else {
    await page.locator('#new-exam').click();
  }
  await expect(page.locator('#exam-title')).toBeEnabled();
  await page.locator('#exam-title').fill(title);
  await page.locator('#exam-duration').fill('15');
  await page.locator('#exam-opens').fill(localDateTime(-5));
  await page.locator('#exam-closes').fill(localDateTime(60));
  await page.locator('#save-exam').click();
  await expect(page.locator('#desk-status')).toContainText('Draft created');
  await expect(page.locator('.exam-list-item').filter({ hasText: title })).toHaveCount(1);
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#delete-exam').click();
  await expect(page.locator('.exam-list-item').filter({ hasText: title })).toHaveCount(0);
  await expect(page.locator('#desk-empty')).toBeVisible();
  await page.locator('#new-exam').click();
  await expect(page.locator('#exam-title')).toBeEmpty();
  await page.goto('/home.html');
  await page.locator('#sign-out').click();
  await expect(page).toHaveURL(/\/login\.html$/);
  expect(issues).toEqual([]);
});
