# Ten-student pilot conditions and outcome

- Date: 2026-09-29 (local Asia/Calcutta).
- One packaged Spring Boot 3.5.16 process on Java 21; local loopback HTTP and MySQL 8.4.11 Docker container, `online_exam_e2e` schema. No production datasource pool override was applied.
- Ten disposable distinct student accounts were created in the e2e schema by copying the already encoded demo hash inside MySQL. Ten separate HTTP sessions were released through a barrier and used ten Python worker threads. The normal login, CSRF, attempt, save, resume, submit and instructor-result APIs were used.
- The exam had two questions worth 2 and 3 marks. All 20 answer saves returned revision 1; all ten starts kept one attempt and original deadline per student. Every submitted score was calculated independently from that student's planned choices and matched the MySQL attempt row and owner instructor roster. All 20 answer rows matched the acknowledged selection and revision.
- Measured after the login barrier: concurrent activity wall time 0.98 s; per-student median 0.27 s, p95 0.33 s, maximum 0.33 s. This is a local demo-scale observation, not a capacity guarantee.
- The packaged app was stopped after the run; the isolated e2e fixtures remain for inspection. The startup log had no application errors. Flyway emitted its previously documented MySQL 8.4 support warning.

Exact command: `powershell -NoProfile -ExecutionPolicy Bypass -File tools/run_ten_student_pilot.ps1`.
Result: `evidence/p09-03-ten-student-pilot.log`.
