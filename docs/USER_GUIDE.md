# User guide

Open `http://127.0.0.1:8080/` after the Java app reports ready. An operator must provision accounts first; there is no public signup. The two roles are instructor and student.

## Instructor

1. Sign in and select **Manage examinations** from the dashboard. Select **Create an exam** to create a draft with a title, description, opening and closing times, and duration in minutes.
2. Add at least one question. Each question needs 2–6 choices, one correct choice and positive marks. Edit or delete draft questions as needed.
3. Publish when the paper is valid. Publication freezes the question text, choices, key, marks and timing. To change a published paper, create a new draft.
4. Open your published paper and review **Student results**. Only your own papers' student results are visible.
5. If a student with an active attempt lost their browser session, use **Reset session** in that paper's active-attempt roster. Confirm the named student. Their previous session ends; they can sign in again and resume the same answers and original deadline. This cannot extend or reopen a finished attempt.

## Student

1. Sign in and select **View examinations** from the dashboard. The examination table shows details, availability and your attempt status. Select **Read instructions**, then **Start exam** to begin at most one attempt for your account.
2. Select one choice per question. Choices and cleared answers save automatically; the page shows **Saved** only after server acknowledgement. Navigate questions and check the remaining time.
3. Refreshing or reconnecting loads your saved choices and the same server deadline. A browser timer or device clock cannot extend the exam. If a save is pending or failed, retry it before submitting. Offline selections are visibly unsaved.
4. Submit when finished. The server grades the stored answers once and shows total marks, earned marks and percentage. If the deadline has passed, server timeout finalizes the same result. Return to the student dashboard and select **View result** to reopen a completed result.
5. Only one active login session is allowed for a student. A second browser receives a session-in-use message while the first remains active. Log out in the first browser, wait for its 15-minute inactivity expiry, or ask the owning instructor for supervised recovery when a live attempt is in progress.

## Limits and help

An incorrect or unanswered single-answer MCQ earns zero for that question; there is no negative marking. Students see an aggregate result, not the answer key. A persistent save error means the selection was not accepted: keep the page open, reconnect and retry while the original deadline permits it. Instructors can see results and sanitized events, but the application does not record camera or screen activity or prevent outside assistance. Contact the exam operator for account provisioning, connectivity or supervised session recovery.
