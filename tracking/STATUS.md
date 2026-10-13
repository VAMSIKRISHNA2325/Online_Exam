# Project status

- Application implementation: CORE_COMPLETE.
- Core tasks: 48 of 48 VERIFIED.
- Conditional deployment tasks: 0 of 2 VERIFIED; public deployment NOT_REQUESTED.
- Required acceptance checks: 40 of 40 PASS; 0 NOT_RUN.
- Observed open blockers: 0.
- Next ready task: none.

- Latest task: CODE-01 VERIFIED (V0062, V0063); shorter private names, shared frontend logic, readable HTML/CSS, bulk options and catalog totals, simpler direct browser checks. See `docs/CODE_REVIEW.md`.
- Final checks: 14 Java unit/HTTP, 69 real-MySQL, four Chromium browser cases; keyboard/offline/safe-text/expiry, 16 responsive views, actual restart and fresh setup/backup restore passed. All selected tests executed with zero failures/errors/skips.
- Local app: READY at `http://127.0.0.1:8080/`, PID 33752, demo profile, persistent `online_exam`. All eight live tables match the pre-refactor snapshot; student accounts and exam/results are intact.
- Existing authorized ngrok tunnel: `https://graveness-undrafted-harsh.ngrok-free.dev`, PID 24044, inspection disabled, upstream loopback 8080. Final public source/readiness and anonymous 401 checks passed without credentials.
- Student roster: 15 supplied students seeded; roll-number usernames/passwords stored as bcrypt. Private input is in ignored `.local/student-roster.tsv`.
- UI/UX exam: PUBLISHED, 30 MCQs, four options/one right answer each, 30 marks, 10-minute attempts. October 10, 2026 window: 01:30?02:00 IST (closed now); original attempts/results preserved.
- B006 Windows running-JAR lock: RESOLVED. Stop the launcher-owned app before Maven rebuilds; MySQL retains data. `E2E_PORT` allows isolated checks on another port.
