# Tracker state contract

JSON files are authoritative. Initial status values describe unexecuted application work.

## Tasks

Allowed states: TODO, READY, IN_PROGRESS, REVIEW, BLOCKED, VERIFIED, DEFERRED. READY/IN_PROGRESS/REVIEW/VERIFIED require all dependencies VERIFIED. BLOCKED requires an unresolved blocker link. Core tasks cannot be DEFERRED without an explicit user-approved scope change represented in the brief and tracker. Optional P12 tasks may remain TODO or DEFERRED without preventing CORE_COMPLETE.

Each task includes ID, phase, title, core flag, priority, requirement IDs, dependencies, owner, acceptance criteria, deliverables, required test IDs, related test IDs, verification IDs, blocker IDs, changed files and next action. VERIFIED requires actual PASS verification and all required test IDs currently PASS. Only the coordinator mutates authoritative trackers during parallel work. related_test_ids is non-gating traceability. P00-P07 tasks verify their bounded acceptance using actual task-linked runs, which may have test_ids=[]; a full catalog test is not PASS until its entire procedure can run and succeeds. This avoids requiring future APIs to verify their own prerequisites.

If a regression is found, reopen the affected VERIFIED task to IN_PROGRESS or BLOCKED, retain its historical evidence, and mark affected test outcomes STALE or FAIL. Return dependent verification gates to TODO until the repaired dependency is verified; preserve completed code and prior records rather than resetting the project.

## Test cases

Outcomes: NOT_RUN, PASS, FAIL, BLOCKED, STALE. `latest_verification_id` is null until a run exists. PASS/FAIL/BLOCKED require a matching run that links this test. STALE points to superseded evidence when available. A relevant code change makes earlier evidence stale; historical runs are retained.

## Verification run example (template, not an executed record)

```json
{
  "id": "V0001",
  "task_ids": ["P03-01"],
  "test_ids": ["T003"],
  "timestamp": "actual ISO-8601 timestamp",
  "command_or_procedure": "exact executed command or browser procedure",
  "source_snapshot": "actual commit plus relevant dirty-diff hash, or manifest hash",
  "environment": "actual Java/MySQL/browser versions and test DB identity without secrets",
  "expected_result": "expected observable behavior",
  "actual_result": "observed outcome and test count",
  "outcome": "PASS",
  "exit_code": 0,
  "evidence_paths": ["evidence/V0001.log"]
}
```

PASS/FAIL evidence paths must refer to real sanitized files inside the repository. For external inability to run a test, record BLOCKED with actual diagnostics; do not invent test output. Manual procedures use null exit_code with a written observation record. A passing run with a nonzero command exit code is invalid. The validator cannot determine whether narrative evidence is truthful or sufficiently complete; the coordinator must inspect it.

## Blocker example (template, not an observed issue)

```json
{
  "id": "B001",
  "task_ids": ["P01-02"],
  "category": "ENVIRONMENT",
  "severity": "S1",
  "status": "OPEN",
  "owner": "coordinator",
  "symptom": "actual observed symptom",
  "reproduction": "exact failing action",
  "evidence_paths": ["evidence/B001-initial.log"],
  "suspected_cause": "evidence-supported diagnosis",
  "attempts": [
    {"number": 1, "hypothesis": "distinct cause", "action": "actual attempted repair", "outcome": "actual result", "verification_ids": ["V0002"]}
  ],
  "safe_alternative": "compatible permitted alternative, or none",
  "next_action": "specific next step",
  "resolution_verification_ids": [],
  "reopened_count": 0
}
```

Categories: ENVIRONMENT, DEPENDENCY, DATABASE, CONTRACT, IMPLEMENTATION, TEST, CREDENTIALS, REQUIREMENT, DEPLOYMENT. Severity: S0/S1/S2/S3. Status: OPEN, INVESTIGATING, WAITING_EXTERNAL, MITIGATED, RESOLVED. Only RESOLVED removes the blocker. Resolution requires passing verification linked to the affected task(s); do not erase failed attempts.

## Evidence, freshness and closure

Store sanitized logs/screenshots/observations under evidence/. Do not record real secrets, cookies, real student records or signed URLs. Link records bidirectionally: task -> verification, test -> latest run, blocker -> affected tasks and closure run. Relevant source changes invalidate outcomes even if the old evidence file exists. Docs-only changes need not invalidate unrelated functional checks.

`node tools/validate_trackers.mjs` checks structural consistency and links. `--require-complete` additionally requires every core task VERIFIED, all required tests PASS and no unresolved blocker linked to core scope. It does not rerun application tests or establish source freshness; those remain mandatory coordinator checks.

The final closure task P11-04 is not self-proving: first record an actual acceptance/evidence audit of all preceding core work, mark P11-04 VERIFIED with that audit, then run the completion validator. If it fails, reopen P11-04 and correct the discrepancy. Append the successful completion-validator run as additional evidence after it executes.
