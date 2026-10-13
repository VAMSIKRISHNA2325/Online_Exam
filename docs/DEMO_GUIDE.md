# Local demonstration

Use the explicit `demo` profile on a disposable database as described in [ACCOUNTS.md](ACCOUNTS.md). Set `DEMO_PASSWORD` privately and start the packaged JAR with `--spring.profiles.active=demo`; the normal profile has no demo accounts. The demo profile seeds `demo_instructor1`, `demo_instructor2`, `demo_student1`, `demo_student2` and `demo_student3` with that private password. Do not include it in screenshots or shared logs.

1. Sign in as `demo_instructor1`, select **Manage examinations**, and select **Create an exam**. Create a paper open now, closing later, with a 30-minute duration. Add two questions worth two and three marks, each with exactly one correct option, then publish.
2. Log out; sign in as `demo_student1` and select **View examinations**. Read the paper's instructions and start it. Choose one correct option and one incorrect option, wait for the saved status, refresh, and confirm both choices and the original deadline remain. Submit and check for 2/5 and 40.00%.
3. Reopen the result from the student dashboard. Log out, sign in as `demo_instructor1`, and find the same score in the paper's results view.
4. For a session-recovery demonstration, create another open paper, start it as a student, save an answer, then remove that browser's session cookie. As the owning instructor, use **Reset session** for the active attempt; the student can sign in and resume without a new deadline.

The automated Playwright journey in `e2e/tests/journey.spec.js` performs the first three steps through the actual UI against isolated MySQL, with independent 2/5 scoring evidence in `evidence/p09-02-browser-full-final.log`. The supervised recovery browser evidence is in `evidence/p08-01-recovery-browser.log`. The ten-student concurrent API pilot is in `evidence/p09-03-ten-student-pilot.log`. These checks never use the normal `online_exam` schema.
