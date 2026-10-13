#!/usr/bin/env node
// Records an already executed, inspected check. It never runs the check itself.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const input = JSON.parse(fs.readFileSync(process.argv[2], 'utf8').replace(/^\uFEFF/, ''));
const read = name => JSON.parse(fs.readFileSync(path.join(root, 'tracking', name), 'utf8'));
const write = (name, data) => fs.writeFileSync(path.join(root, 'tracking', name), JSON.stringify(data, null, 2) + '\n');
const taskData = read('tasks.json');
const testData = read('tests.json');
const verification = read('verification.json');
const blockers = read('blockers.json');
const task = taskData.tasks.find(row => row.id === input.task_id);
if (!task) throw new Error(`Unknown task ${input.task_id}`);
if (!['PASS', 'FAIL', 'BLOCKED'].includes(input.outcome)) throw new Error('Invalid outcome');
if (!Array.isArray(input.evidence_paths) || !input.evidence_paths.length) throw new Error('Evidence file required');
for (const name of input.evidence_paths) {
  if (!name.startsWith('evidence/') || !fs.statSync(path.join(root, name)).isFile()) throw new Error(`Missing evidence ${name}`);
}
if (input.outcome === 'PASS' && input.exit_code !== null && input.exit_code !== 0) throw new Error('Passing command has nonzero exit code');
const changedFiles = input.changed_files ?? task.changed_files;
const hash = crypto.createHash('sha256');
for (const name of [...changedFiles].sort()) {
  const absolute = path.join(root, name);
  if (!fs.existsSync(absolute) || !fs.statSync(absolute).isFile()) throw new Error(`Missing snapshot file ${name}`);
  hash.update(name).update('\0').update(fs.readFileSync(absolute)).update('\0');
}
const id = `V${String(verification.runs.length + 1).padStart(4, '0')}`;
const run = {
  id,
  task_ids: [task.id],
  test_ids: input.test_ids ?? [],
  timestamp: new Date().toISOString(),
  command_or_procedure: input.command_or_procedure,
  source_snapshot: `sha256-manifest:${hash.digest('hex')}`,
  environment: input.environment,
  expected_result: input.expected_result,
  actual_result: input.actual_result,
  outcome: input.outcome,
  exit_code: input.exit_code,
  evidence_paths: input.evidence_paths
};
for (const key of ['command_or_procedure', 'environment', 'expected_result', 'actual_result']) if (!run[key]) throw new Error(`Missing ${key}`);
for (const testId of run.test_ids) {
  const test = testData.tests.find(row => row.id === testId);
  if (!test) throw new Error(`Unknown test ${testId}`);
  test.status = run.outcome;
  test.latest_verification_id = id;
}
task.changed_files = [...new Set([...task.changed_files, ...changedFiles])];
task.verification_ids.push(id);
if (run.outcome === 'PASS' && input.verify_task) {
  for (const dependency of task.dependencies) if (taskData.tasks.find(row => row.id === dependency)?.status !== 'VERIFIED') throw new Error(`Unverified dependency ${dependency}`);
  for (const testId of task.required_test_ids) if (testData.tests.find(row => row.id === testId)?.status !== 'PASS') throw new Error(`Required test ${testId} is not PASS`);
  if (task.blocker_ids.some(blockerId => blockers.blockers.find(row => row.id === blockerId)?.status !== 'RESOLVED')) throw new Error('Unresolved blocker');
  task.status = 'VERIFIED';
  task.owner ||= 'coordinator';
  task.next_action = 'Verified; continue to the next ready task';
}
verification.runs.push(run);
write('tasks.json', taskData);
write('tests.json', testData);
write('verification.json', verification);
const core = taskData.tasks.filter(row => row.core);
const required = testData.tests.filter(row => row.required);
const ready = taskData.tasks.find(row => row.status === 'TODO'
  && (row.phase !== 'P12' || taskData.deployment_status === 'REQUESTED')
  && row.dependencies.every(id => taskData.tasks.find(dep => dep.id === id)?.status === 'VERIFIED'));
fs.writeFileSync(path.join(root, 'tracking', 'STATUS.md'), `# Project status\n\n- Application implementation: ${taskData.implementation_status}.\n- Core tasks: ${core.filter(row => row.status === 'VERIFIED').length} of ${core.length} VERIFIED.\n- Conditional deployment tasks: ${taskData.tasks.filter(row => row.phase === 'P12' && row.status === 'VERIFIED').length} of 2 VERIFIED; public deployment ${taskData.deployment_status}.\n- Required acceptance checks: ${required.filter(row => row.status === 'PASS').length} of ${required.length} PASS; ${required.filter(row => row.status === 'NOT_RUN').length} NOT_RUN.\n- Observed open blockers: ${blockers.blockers.filter(row => row.status !== 'RESOLVED').length}.\n- Next ready task: ${ready?.id ?? 'none'}.\n`);
console.log(`${id}: ${task.id} ${run.outcome}; task ${task.status}`);
