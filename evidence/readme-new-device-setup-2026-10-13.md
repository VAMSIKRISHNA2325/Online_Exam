# New-device README check, 2026-10-13

- Verified five README links resolve, eight referenced setup files exist, and 20 Markdown code fences are balanced.
- Parsed `tools/start-local.ps1` and `tools/stop-local.ps1` without PowerShell syntax errors. Checked the README's copy/build/start/stop commands against these scripts and the Maven Wrapper files.
- Verified the documented PowerShell `.env` read obtains a configured MySQL application password without printing it. `docker compose version` returned v5.4.0. `git diff --check -- README.md` returned zero.
- No Java app, MySQL data or public tunnel was changed by this documentation check. These are local checks of the instructions; a setup on a separate physical device was not performed.
- The repository-wide `tools/check_documentation.py` could not complete during this task because `START_HERE.md` and other root documents were being moved under `docs/` in the workspace. Those moves were left untouched; the README-specific link check above passed.
