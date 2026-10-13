# Final scope and source audit

Review date: 2026-09-29. Checked the current Java/HTML/CSS/vanilla JavaScript/MySQL source, `docs/REQUIREMENTS.md`, the authoritative task/test ledger, the source manifest and absence of placeholder/disabled-test markers. This directory has no `.git` repository, so there is no Git diff to review; the deterministic manifest is the release-source snapshot.

| Requirement | Implemented boundary | Passing evidence |
|---|---|---|
| R01 | Spring Security login/logout, encoded MySQL accounts, role/owner checks | T003–T006, T031, V0014/V0028/V0037 |
| R02 | Owner-only draft exam/question/option CRUD in `ExamAuthoringService` | T007, V0017/V0021 |
| R03 | Validated, frozen publication under exam row lock | T008/T009, V0019 |
| R04 | Published-only student catalog and server availability | T010, V0020/V0022 |
| R05 | One MySQL attempt and original deadline per student/exam | T011, V0022 |
| R06 | Choice save/clear, persisted revision and acknowledgement | T013/T014, V0026 |
| R07 | Owner-only resume and server-controlled deadline | T015/T023, V0029/V0031/V0037 |
| R08 | Shared attempt-row lock for save, submit and timeout | T016/T018–T021/T026, V0032/V0033/V0039 |
| R09 | Java grading from stored answers/keys and persistent result | T017/T032/T033, V0032/V0042/V0045 |
| R10 | Own aggregate student result; owner roster/events | T012/T024/T025, V0034/V0040 |
| R11 | First student session preserved, duplicate denied | T022/T040, V0016/V0023 |
| R12 | Sanitized duplicate/recovery audit and scoped reset | T023/T025, V0037 |
| R13 | Java/MySQL/browser, concurrency, security and recovery tests | T026–T030/T033/T037/T039, V0039–V0043 |
| R14 | Wrapper, Flyway, demo/provision, clean setup, docs, package | T001/T002/T032/T034/T036/T038, V0013/V0044–V0047 |

Source review found no `TODO`, `FIXME`, `UnsupportedOperationException`, disabled JUnit tests, or Maven skip flag in application/test/build files. Student views are separate from instructor answer-key views. Scores and attempts are MySQL-backed, and front-end storage is not an authoritative record. `AnswerService` and `FinalizationService` both take `AccessControl`'s locked attempt path and recheck state and deadline after locking. The latest full Java/MySQL and browser gates are scheduled to be rerun on this manifest at P11-02. The two resolved blocker records retain their original failure and passing repair evidence.

The only conditional scope is public deployment: no configured/authorized hosting target exists, and its tracker state is `NOT_REQUESTED`. Local package startup, clean setup and backup/restore have passed. Remaining known maintenance risks are the Flyway/MySQL vendor certification gap and eventual Spring Boot upgrade; neither was hidden as a passing deployment test.
