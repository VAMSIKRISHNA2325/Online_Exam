const { test, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const password = process.env.DEMO_PASSWORD ||
  fs.readFileSync(path.join(root, '.local', 'demo-password.txt'), 'utf8').trim();

test('student signs in and opens the real MySQL catalog', async ({ page }) => {
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/login.html');
  await page.locator('#username').fill('demo_student1');
  await page.locator('#password').fill(password);
  await page.locator('#sign-in').click();
  await expect(page).toHaveURL(/\/home\.html$/);
  await page.locator('#student-link').click();
  await expect(page).toHaveURL(/\/student\.html$/);
  await expect(page.locator('#catalog-loading')).toBeHidden();
  await expect(page.locator('#catalog-count')).toHaveText(/^\d{2}$/);
  expect(errors).toEqual([]);
});

test('instructor signs in and opens owned MySQL papers', async ({ page }) => {
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/login.html');
  await page.locator('#username').fill('demo_instructor1');
  await page.locator('#password').fill(password);
  await page.locator('#sign-in').click();
  await expect(page).toHaveURL(/\/home\.html$/);
  await page.locator('#instructor-link').click();
  await expect(page).toHaveURL(/\/instructor\.html$/);
  await expect(page.locator('#desk-loading')).toBeHidden();
  await expect(page.locator('#exam-count')).toHaveText(/^\d{2}$/);
  expect(errors).toEqual([]);
});
