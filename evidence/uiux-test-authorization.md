# Scheduled test creation and browser verification authorization

- User requested creation of a basic UI/UX test: 30 questions, four options and one right answer, 10 minutes, scheduled 10 October 2026 from 1:30 to 1:40 AM.
- Asia/Calcutta is the client timezone. The requested window was upcoming during creation. The persisted UTC interval is 9 October 2026 20:00-20:10.
- Created and published through the authenticated local instructor API with CSRF protection; no existing examination was overwritten.
- Automatic approval review rejected the first public browser check because it would send a stored instructor password through ngrok. The rejected script did not execute.
- User quoted that exact credential-transmission warning and explicitly replied: "run it. no problem".
- Retried the same browser check with this specific authorization; it passed for instructor and student views. Verification sessions were logged out, existing student sessions were preserved, and no student test attempt was started.
- Browser screenshots contain schedule fields and the student catalog only. Answer keys, passwords, cookies and CSRF tokens are absent from evidence. Private working paper/key files stay under ignored .local/ and are not served.
