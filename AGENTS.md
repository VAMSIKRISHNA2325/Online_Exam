# Online Examination System working instructions

Read `CODEX_IMPLEMENTATION_PLAN.md` before implementation. These project instructions operate within applicable system, user and existing repository instructions; preserve and merge existing guidance.

## Required stack and scope

Java 21; Spring Boot/MVC/Security/JDBC; vanilla HTML/CSS/JavaScript; MySQL. Flyway migrations, Maven Wrapper, Java tests and JavaScript Playwright checks are proposed supporting tools. Pin compatible versions. Core workflows must persist to MySQL. Follow the two-role, single-answer MCQ scope, first-session-preserving login rule and server-controlled timing in the brief.

## Every work cycle

Inspect current files and trackers; choose one ready task; implement a bounded change; execute relevant verification; fix failures; record evidence; continue. Keep short outcome/decision records, not a private reasoning transcript. Each worker owns one implementation task; coordinator owns trackers and integration. Do not mark code written as VERIFIED.

## State and continuity

Authoritative state is in tracking/tasks.json, tests.json, blockers.json and verification.json. Update WORKLOG.md and HANDOFF.md after milestones and before stopping. Inspect git status and preserve user changes on every resumed session. Do not replace existing trackers with fresh templates. tracking/STATUS.md summaries must agree with JSON.

## Blockers

Record reproducible symptoms, affected tasks, actual evidence and next action. Try at most three distinct evidence-supported repair hypotheses before a diagnostic checkpoint; continue independent tasks. Close only after the original failed check and nearby regression pass. Never fix a failure by disabling security, accepting client scores, faking persistence or hiding skipped tests. Ask only for real external access/decisions that remain necessary.

## Verification and release

Implement the exact command contracts in the brief: Maven unit checks, real-MySQL integration profile, Playwright browser flow, clean setup and tracker validation. Tests planned/skipped/blocked are not PASS. Relevant code changes stale their evidence. Run broad regression at integration/final gates, not after every cosmetic edit.

All save and finalize operations must share an attempt-row transaction lock and recheck ownership, deadline and state. Never expose answer keys to students. Every required result, answer, event and exam must use MySQL.

Final delivery needs verified core tasks, passing applicable acceptance checks, runnable source, documentation and exact commands. Public deployment requires an actual configured/authorized target and separate evidence. Continue through ready work; if external blockers remain, disclose incomplete scope and the next exact action.
