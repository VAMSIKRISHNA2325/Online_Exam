# Ngrok authorization and startup evidence

- User requested: "start ngrok and give the link".
- Automatic approval review initially rejected startup: public exposure of the local examination app could disclose private exam/user data to ngrok, and that specific disclosure needed authorization. The rejected command did not run.
- User explicitly acknowledged the stated risk and replied: "no problem. run it".
- Read-only development counts found five demo accounts, no other users, one exam, one attempt and one answer. No private record contents or credentials were printed. Public access checks returned 401 for protected APIs.
- Retried the same startup action with the user's explicit authorization; it succeeded. Ngrok 3.39.11 uses the existing valid private configuration. HTTP traffic inspection is disabled. App/database configuration and data were preserved.
- Public HTTPS readiness, login HTML and matching dashboard CSS passed. Anonymous identity, student exam, instructor exam and result requests returned 401. See ngrok-public-smoke.json.

Verified public URL: https://graveness-undrafted-harsh.ngrok-free.dev
