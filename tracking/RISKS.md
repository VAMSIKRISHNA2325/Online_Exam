# Anticipated risks

Risks are possibilities, not observed blockers. Create blocker records only after evidence appears.

| Risk | Effect | Mitigation |
|---|---|---|
| JDK/dependency access missing | Build cannot run | Inspect first, pin compatible versions, record exact missing capability |
| Docker unavailable | Container tests cannot run | Use isolated native MySQL profile if permitted; never claim substitute DB coverage |
| Mismatched API contracts | UI/backend integration defects | Freeze safe DTOs and errors, integrate an early vertical slice |
| Timer/save concurrency bugs | Lost answers or invalid scores | Shared attempt locking, authoritative deadlines, revision checks, race tests |
| Lost browser session | Student cannot resume | Scoped audited recovery preserving original deadline |
| Long run/context interruption | Repeated work or status drift | Durable trackers and exact handoff |
| Shared agent file edits | Conflicts or accidental overwrite | Coordinator-owned trackers; disjoint ownership/worktrees |
| Proctoring claims exceed capabilities | Misleading demonstration | Explicitly document web-app limits and future integration scope |
| Public hosting unavailable | No live URL | Complete local release, keep deployment conditional |
| Flyway 11.7.2 does not certify MySQL 8.4 | Migration defects may appear despite successful connection | Run every migration and upgrade path on real MySQL 8.4.11; retain warning in evidence; select a supported Flyway version when available |
## Maintenance note (2026-09-29)

Spring Boot 3.5.16 is the final open-source 3.5.x release. Plan and test a Spring Boot 4 upgrade before long-term operation. This does not replace required P08 security hardening or release verification.
