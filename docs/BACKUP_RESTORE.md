# MySQL backup and restore

The MySQL volume is the durable store for users, exams, questions, attempts, answer revisions, grades and security events. Keep backups private because they contain account password hashes and student records. A copy of the JAR alone cannot recover results.

## Rehearsed local procedure

From the project root with Docker MySQL healthy and a private `.env`, run:

```powershell
python tools/rehearse_clean_setup_backup.py
```

This check creates a new source copy under ignored `.local/`, builds it with the Maven Wrapper, and starts the JAR against a newly created empty `online_exam_clean_p10_*` schema. It verifies Flyway V1, zero default accounts, HTTP readiness and the login page. It then makes a consistent `mysqldump` of the disposable `online_exam_e2e` schema and restores into a **different, newly created** `online_exam_restore_p10_*` schema. It compares every table's row count and a deterministic ordered dump of all table data, including Flyway history. It leaves the private backup file and both schemas for inspection. The command does not drop or overwrite `online_exam` or any existing schema.

The build uses the configured Java 21 JDK and the existing Maven dependency cache; no application `.env`, `.local` account password file, compiled class or target artifact is copied to the fresh source directory. The database passwords are supplied only through process environment. For a fully new machine, follow [README.md](../README.md) to install Java 21, bring up MySQL and let the wrapper fetch dependencies.

## Operational recovery rules

For a live deployment, take a MySQL 8.4 consistent backup with `mysqldump --single-transaction --quick --order-by-primary --set-gtid-purged=OFF` and protect the resulting file with host access controls and encryption. Verify a backup by restoring to a separately named database first, then compare Flyway version, table counts and representative result/answer records before planning any production cutover. Stop application writes during a final cutover or use a backup plus transaction-log point-in-time method; a backup taken while writes continue represents one earlier database snapshot. Never import a rehearsal dump over the normal `online_exam` schema. Document the selected backup retention and restore target for the actual host.

The exact isolated local rehearsal result is in `evidence/p10-02-clean-setup-backup.log`, with its build log in `evidence/p10-02-clean-build.log`. SQL backup contents stay in ignored `.local/` and are not published as evidence.
