#!/usr/bin/env node
// Structural/evidence-reference validator. This does not execute application tests.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const requireComplete = process.argv.includes('--require-complete');
const errors = [];
const complain = message => errors.push(message);
const nonempty = value => typeof value === 'string' && value.trim().length > 0;
const list = value => Array.isArray(value) ? value : [];
function read(name, key) {
  try {
    const document = JSON.parse(fs.readFileSync(path.join(root, 'tracking', name), 'utf8'));
    if (document.schema_version !== 1) complain(`${name}: schema_version must be 1`);
    if (!Array.isArray(document[key])) complain(`${name}: ${key} must be an array`);
    return { document, rows: list(document[key]) };
  } catch (error) {
    complain(`${name}: ${error.message}`);
    return { document: {}, rows: [] };
  }
}
const taskData = read('tasks.json', 'tasks');
const tasks = taskData.rows;
if (!['NOT_STARTED','IN_PROGRESS','BLOCKED','CORE_COMPLETE'].includes(taskData.document.implementation_status)) complain('tasks.json: invalid implementation_status');
if (!['NOT_REQUESTED','REQUESTED','BLOCKED','DEPLOYED'].includes(taskData.document.deployment_status)) complain('tasks.json: invalid deployment_status');
const tests = read('tests.json', 'tests').rows;
const blockers = read('blockers.json', 'blockers').rows;
const runs = read('verification.json', 'runs').rows;
function makeMap(rows, label) {
  const result = new Map();
  for (const row of rows) {
    if (!row || !nonempty(row.id)) { complain(`${label}: missing ID`); continue; }
    if (result.has(row.id)) complain(`${label}: duplicate ID ${row.id}`);
    result.set(row.id, row);
  }
  return result;
}
const taskById = makeMap(tasks, 'task');
const testById = makeMap(tests, 'test');
const blockerById = makeMap(blockers, 'blocker');
const runById = makeMap(runs, 'verification');
const taskStates = new Set(['TODO','READY','IN_PROGRESS','REVIEW','BLOCKED','VERIFIED','DEFERRED']);
const testStates = new Set(['NOT_RUN','PASS','FAIL','BLOCKED','STALE']);
const blockerStates = new Set(['OPEN','INVESTIGATING','WAITING_EXTERNAL','MITIGATED','RESOLVED']);
const categories = new Set(['ENVIRONMENT','DEPENDENCY','DATABASE','CONTRACT','IMPLEMENTATION','TEST','CREDENTIALS','REQUIREMENT','DEPLOYMENT']);
function requiredStrings(row, names, label) {
  for (const name of names) if (!nonempty(row[name])) complain(`${label}: missing ${name}`);
}
function arrayField(row, name, label, allowEmpty = true) {
  if (!Array.isArray(row[name]) || (!allowEmpty && !row[name].length)) complain(`${label}: invalid ${name}`);
}
function evidencePaths(row, label, mandatory) {
  const refs = list(row.evidence_paths);
  if (mandatory && !refs.length) complain(`${label}: actual evidence file required`);
  for (const ref of refs) {
    if (!nonempty(ref) || path.isAbsolute(ref)) { complain(`${label}: invalid relative evidence path`); continue; }
    const absolute = path.resolve(root, ref);
    if (!absolute.startsWith(root + path.sep)) { complain(`${label}: evidence escapes project`); continue; }
    try {
      const real = fs.realpathSync(absolute);
      if (!real.startsWith(fs.realpathSync(root) + path.sep) || !fs.statSync(real).isFile()) complain(`${label}: unsafe/non-file evidence ${ref}`);
    } catch { complain(`${label}: missing evidence ${ref}`); }
  }
}
function linkExists(ids, map, label) {
  for (const id of list(ids)) if (!map.has(id)) complain(`${label}: unknown ID ${id}`);
}

for (const run of runs) {
  if (!run) continue;
  const label = `verification ${run.id}`;
  requiredStrings(run, ['timestamp','command_or_procedure','source_snapshot','environment','expected_result','actual_result'], label);
  if (nonempty(run.timestamp) && Number.isNaN(Date.parse(run.timestamp))) complain(`${label}: invalid timestamp`);
  if (!['PASS','FAIL','BLOCKED'].includes(run.outcome)) complain(`${label}: invalid outcome`);
  arrayField(run, 'task_ids', label, false);
  arrayField(run, 'test_ids', label);
  linkExists(run.task_ids, taskById, label);
  linkExists(run.test_ids, testById, label);
  if (run.exit_code !== null && !Number.isInteger(run.exit_code)) complain(`${label}: exit_code must be integer or null`);
  if (run.outcome === 'PASS' && run.exit_code !== null && run.exit_code !== 0) complain(`${label}: PASS cannot have nonzero exit code`);
  evidencePaths(run, label, true);
}

for (const test of tests) {
  if (!test) continue;
  const label = `test ${test.id}`;
  requiredStrings(test, ['title','kind','procedure','expected_result'], label);
  if (typeof test.required !== 'boolean') complain(`${label}: required must be boolean`);
  if (!testStates.has(test.status)) complain(`${label}: invalid status ${test.status}`);
  if (['PASS','FAIL','BLOCKED'].includes(test.status)) {
    const run = runById.get(test.latest_verification_id);
    if (!run || run.outcome !== test.status || !list(run.test_ids).includes(test.id)) complain(`${label}: latest applicable run must match status and link this test`);
  } else if (test.latest_verification_id !== null && !runById.has(test.latest_verification_id)) complain(`${label}: unknown prior verification`);
}

for (const blocker of blockers) {
  if (!blocker) continue;
  const label = `blocker ${blocker.id}`;
  requiredStrings(blocker, ['owner','symptom','reproduction','suspected_cause','safe_alternative','next_action'], label);
  if (!blockerStates.has(blocker.status)) complain(`${label}: invalid status`);
  if (!categories.has(blocker.category)) complain(`${label}: invalid category`);
  if (!['S0','S1','S2','S3'].includes(blocker.severity)) complain(`${label}: invalid severity`);
  arrayField(blocker, 'task_ids', label, false);
  arrayField(blocker, 'attempts', label);
  arrayField(blocker, 'resolution_verification_ids', label);
  linkExists(blocker.task_ids, taskById, label);
  evidencePaths(blocker, label, true);
  for (const attempt of list(blocker.attempts)) {
    requiredStrings(attempt, ['hypothesis','action','outcome'], label);
    linkExists(attempt.verification_ids, runById, label);
  }
  for (const id of list(blocker.task_ids)) {
    if (!list(taskById.get(id)?.blocker_ids).includes(blocker.id)) complain(`${label}: task ${id} lacks reverse blocker link`);
  }
  if (blocker.status === 'RESOLVED') {
    if (!list(blocker.resolution_verification_ids).length) complain(`${label}: closure evidence required`);
    for (const id of list(blocker.resolution_verification_ids)) {
      const run = runById.get(id);
      if (!run || run.outcome !== 'PASS' || !list(run.task_ids).some(t => list(blocker.task_ids).includes(t))) complain(`${label}: closure ${id} must pass and link an affected task`);
    }
  }
}

for (const task of tasks) {
  if (!task) continue;
  const label = `task ${task.id}`;
  requiredStrings(task, ['phase','title','next_action'], label);
  for (const field of ['requirement_ids','dependencies','acceptance_criteria','deliverables','required_test_ids','verification_ids','blocker_ids','changed_files']) arrayField(task,field,label,!['requirement_ids','acceptance_criteria','deliverables'].includes(field));
  if (typeof task.core !== 'boolean') complain(`${label}: core must be boolean`);
  if (!taskStates.has(task.status)) complain(`${label}: invalid status`);
  linkExists(task.dependencies, taskById, label);
  linkExists(task.required_test_ids, testById, label);
  linkExists(task.related_test_ids, testById, label);
  linkExists(task.verification_ids, runById, label);
  linkExists(task.blocker_ids, blockerById, label);
  for (const id of list(task.blocker_ids)) if (!list(blockerById.get(id)?.task_ids).includes(task.id)) complain(`${label}: blocker ${id} lacks reverse task link`);
  if (['READY','IN_PROGRESS','REVIEW','VERIFIED'].includes(task.status)) {
    for (const id of list(task.dependencies)) if (taskById.get(id)?.status !== 'VERIFIED') complain(`${label}: dependency ${id} is not VERIFIED`);
  }
  if (['IN_PROGRESS','REVIEW','VERIFIED'].includes(task.status) && !nonempty(task.owner)) complain(`${label}: active/verified work needs owner`);
  if (task.core && task.status === 'DEFERRED') complain(`${label}: core cannot be silently deferred`);
  const unresolved = list(task.blocker_ids).filter(id => blockerById.get(id)?.status !== 'RESOLVED');
  if (task.status === 'BLOCKED' && !unresolved.length) complain(`${label}: BLOCKED needs unresolved blocker`);
  if (task.status === 'VERIFIED') {
    if (unresolved.length) complain(`${label}: unresolved blocker prevents verification`);
    if (!list(task.verification_ids).length) complain(`${label}: actual verification required`);
    const passing = list(task.verification_ids).some(id => runById.get(id)?.outcome === 'PASS' && list(runById.get(id)?.task_ids).includes(task.id));
    if (!passing) complain(`${label}: no passing run links this task`);
    for (const id of list(task.required_test_ids)) if (testById.get(id)?.status !== 'PASS') complain(`${label}: required test ${id} not PASS`);
  }
}

// Cycles make tasks impossible to schedule and must fail even in the initial pack.
const visiting = new Set(); const visited = new Set();
function visit(id) {
  if (visiting.has(id)) { complain(`dependency cycle at ${id}`); return; }
  if (visited.has(id) || !taskById.has(id)) return;
  visiting.add(id);
  for (const dependency of list(taskById.get(id).dependencies)) visit(dependency);
  visiting.delete(id); visited.add(id);
}
for (const id of taskById.keys()) visit(id);
for (const test of tests) if (test?.required && !tasks.some(t => list(t?.required_test_ids).includes(test.id))) complain(`required test ${test.id} is not assigned to any task`);
const activeOwners = new Map();
for (const task of tasks.filter(t => t?.status === 'IN_PROGRESS' && nonempty(t.owner))) {
  if (activeOwners.has(task.owner)) complain(`${task.owner}: multiple IN_PROGRESS implementation tasks`);
  activeOwners.set(task.owner, task.id);
}
const core = tasks.filter(t => t?.core);
const requiredTests = tests.filter(t => t?.required);
if (!core.length || !requiredTests.length) complain('Core tasks and required test catalog must not be empty');
if (requireComplete || taskData.document.implementation_status === 'CORE_COMPLETE' || taskData.document.deployment_status === 'DEPLOYED') {
  const unfinished = core.filter(t => t.status !== 'VERIFIED');
  const unpassed = requiredTests.filter(t => t.status !== 'PASS');
  if (unfinished.length) complain(`${unfinished.length} core tasks are not VERIFIED`);
  if (unpassed.length) complain(`${unpassed.length} required tests are not PASS`);
  for (const blocker of blockers) if (blocker?.status !== 'RESOLVED' && list(blocker.task_ids).some(id => taskById.get(id)?.core)) complain(`${blocker.id}: unresolved blocker affects core scope`);
}
if (taskData.document.deployment_status === 'DEPLOYED') {
  const deploymentTasks = tasks.filter(t => t?.phase === 'P12');
  if (!deploymentTasks.length || deploymentTasks.some(t => t.status !== 'VERIFIED')) complain('DEPLOYED requires verified deployment tasks and actual evidence');
}
const summary = {
  structureValid: errors.length === 0,
  mode: requireComplete ? 'require-complete' : 'structure',
  coreVerified: core.filter(t => t.status === 'VERIFIED').length,
  coreTotal: core.length,
  requiredTestsPassed: requiredTests.filter(t => t.status === 'PASS').length,
  requiredTestsTotal: requiredTests.length,
  observedOpenBlockers: blockers.filter(b => b?.status !== 'RESOLVED').length,
  applicationVerifiedByThisUtility: false,
  errors
};
console.log(JSON.stringify(summary, null, 2));
process.exitCode = errors.length ? 1 : 0;
