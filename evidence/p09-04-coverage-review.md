# P09 required-check coverage review

Review date: 2026-09-29. Source: `tracking/tests.json`, `tracking/verification.json`, `tracking/blockers.json`, and `node tools/validate_trackers.mjs`.

- 36 of 40 required checks are PASS. Every PASS check points to a passing verification record with an existing evidence file; the tracker validator checked these links.
- The four NOT_RUN checks are T001 clean installation, T032 backup/restore, T034 documentation run-through, and T038 release package startup. They are assigned to P10-01, P10-02, and P10-03; P11-02 requires all 40.
- Application behavior, security, real-MySQL persistence, browser flow, resilience and the ten-student pilot have evidence through V0042. The latest full Maven profile ran 14 unit/HTTP plus 68 MySQL tests; the latest full Playwright suite ran four Chromium cases. Test selection and zero skips were checked in `evidence/p09-02-test-selection.log`.
- B001 and B002 are RESOLVED, with failed original checks and passing regression evidence retained. There are zero observed open blockers.
- Subsequent source changes require the P11-02 final integrated gates; this review does not substitute for those runs.

Conclusion: P09 behavior and security coverage is accounted for; remaining release checks are explicit P10-P11 work.
