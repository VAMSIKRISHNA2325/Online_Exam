const { defineConfig } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const settings = {};
const envFile = path.join(root, '.env');
if (fs.existsSync(envFile)) {
  for (const line of fs.readFileSync(envFile, 'utf8').split(/\r?\n/)) {
    const split = line.indexOf('=');
    if (split > 0 && !line.startsWith('#')) settings[line.slice(0, split)] = line.slice(split + 1);
  }
}
const passwordFile = path.join(root, '.local', 'demo-password.txt');
const demoPassword = process.env.DEMO_PASSWORD ||
  (fs.existsSync(passwordFile) ? fs.readFileSync(passwordFile, 'utf8').trim() : '');
const databasePassword = process.env.DB_PASSWORD || settings.MYSQL_PASSWORD || '';
const databaseUrl = process.env.E2E_DB_URL ||
  'jdbc:mysql://127.0.0.1:3307/online_exam_e2e?connectionTimeZone=UTC';
if (!databaseUrl.startsWith('jdbc:mysql://') || new URL(databaseUrl.slice(5)).pathname !== '/online_exam_e2e'
    || !databasePassword || !demoPassword) {
  throw new Error('Isolated online_exam_e2e database and private credentials are required');
}
const localJava = path.join(root, '.local', 'jdk', 'jdk-21.0.12.1+1', 'bin',
  process.platform === 'win32' ? 'java.exe' : 'java');
const java = process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  : (fs.existsSync(localJava) ? localJava : 'java');
const jar = path.join(root, 'target', 'online-exam-0.1.0-SNAPSHOT.jar');
if (!fs.existsSync(jar)) throw new Error('Build the packaged application before Playwright');
const port = Number(process.env.E2E_PORT || 8080);
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid E2E_PORT');
const baseURL = `http://127.0.0.1:${port}`;

module.exports = defineConfig({
  testDir: './tests',
  timeout: 45_000,
  expect: { timeout: 10_000 },
  retries: 0,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  outputDir: 'test-results',
  use: {
    baseURL,
    browserName: 'chromium',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure'
  },
  webServer: {
    command: `"${java}" -jar "${jar}" --spring.profiles.active=demo --server.address=127.0.0.1`,
    url: `${baseURL}/api/ready`,
    timeout: 90_000,
    reuseExistingServer: false,
    env: {
      ...process.env,
      DB_URL: databaseUrl,
      DB_USER: process.env.E2E_DB_USER || 'online_exam',
      DB_PASSWORD: databasePassword,
      DEMO_PASSWORD: demoPassword,
      PORT: String(port),
      DEBUG: 'false'
    }
  }
});
