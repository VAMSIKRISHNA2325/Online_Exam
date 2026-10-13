# Start here

The implemented local Online Examination System is described in [README.md](README.md). Follow its Java 21/MySQL setup and packaged startup commands, then use [the user guide](docs/USER_GUIDE.md) or [demo guide](docs/DEMO_GUIDE.md). API, schema, architecture, backup and conditional hosting guidance are linked from the README and `docs/`.

`CODEX_IMPLEMENTATION_PLAN.md` is the original build specification. `AGENTS.md` contains working rules. Current task/test state, blockers and evidence are in `tracking/`; run `node tools/validate_trackers.mjs --require-complete` to check the final ledger. That command validates evidence structure; Maven/MySQL and Playwright commands in the README execute the actual application checks.

Public hosting is a separate conditional task. The local release URL is `http://127.0.0.1:8080/` while the Java process runs; there is no public URL or configured hosting target in this package.
