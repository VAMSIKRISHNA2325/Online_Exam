# Conditional hosting review

- `deploy/runtime.env.example` lists the actual runtime variable names used by `application.yml`: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `PORT`, and `COOKIE_SECURE`. It contains placeholders only and is not loaded automatically.
- `docs/DEPLOYMENT.md` describes the Java/MySQL/HTTPS/secrets/backup requirements, single-instance session limits, account provisioning, migration and remote smoke gates. The linked local startup and backup rehearsals passed in V0044 and V0045.
- No hosting target, domain, certificate, cloud account or deployment authorization is configured in this workspace. The conditional P12 deployment tasks remain unverified and `tracking/tasks.json` retains `deployment_status: NOT_REQUESTED`.

This is a prepared package and runbook, not evidence of a public deployment.
