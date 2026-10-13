# Required UI action sweep

| Screen | Exercised controls and outcome | Evidence |
|---|---|---|
| Login and account | Wrong password stays denied; password show/hide works; student and instructor role links open the correct desk; Sign out invalidates the session. | `evidence/V0018-p03-04-browser.log`, `evidence/p09-02-browser-full-final.log` |
| Instructor drafts | Empty-desk Create, sidebar Create, metadata create/edit, Add/Edit/Delete question, Add/Remove option, Close editor, invalid empty publish, publish, draft delete. The published paper is immutable and persisted in MySQL. | `evidence/p09-02-browser-full-final.log`, `evidence/p09-02-ui-published-paper.png` |
| Instructor monitoring | Refresh active attempts and results; owner Reset session; result row contains the student's persisted 2/5 score. | `evidence/p09-02-browser-full-final.log`, `evidence/p08-01-recovery-browser-live.log`, `evidence/p09-02-ui-instructor-review.png` |
| Student desk | Refresh catalog, open/close/cancel instructions, start, resume, and View result. | `evidence/p09-02-browser-full-final.log`, `evidence/V0031-p07-02-browser.log` |
| Exam player | Select, clear, navigate, refresh, cancel/confirm submit, persisted 2/5 result, and retry a failed result fetch. Pending/failed/offline save disables Submit; retry saves; post-expiry change is rejected. | `evidence/p09-02-browser-full-final.log`, `evidence/p08-04-resilience-browser.log`, `evidence/p09-02-ui-student-result.png` |
| Keyboard and mobile | Keyboard-only login/answer/navigation/submit and 360px layout passed with visible focus and no horizontal overflow. | `evidence/p07-04-keyboard-offline-browser.log` |

The Playwright journey monitors page exceptions, console errors, failed requests and unexpected HTTP errors. Its final passing run had none. The deliberately rejected empty-paper publication (`409`) and unauthenticated current-user probes (`401`) were handled by their UI paths. The first run exposed a blocked external font stylesheet; removing that import eliminated the Content Security Policy console errors. A later run exposed an instructor initialization race; disabling Create until initialization finishes and ignoring stale paper responses resolved it. Both failed runs and the passing rerun are retained.
