# EnthusiaLoreItems operator guide

This guide is for server operators running the WP-04 release candidate and later production releases. It documents supported installation, upgrade, backup, restore, degraded-mode recovery, queue/review recovery, staged deployment, rollback, and incident collection. It does **not** claim live Paper/Leaf acceptance; that belongs to WP-05.

## Supported runtime and files

Target runtime:

- Java 21
- Paper/Leaf 1.21.11-compatible server
- SQLite
- single SMP server; no proxy or multi-server coordination
- Geyser/Floodgate-compatible player identities

Primary plugin data lives under `plugins/EnthusiaLoreItems/`.

Important files/directories:

- `config.yml` — operator configuration
- `loreitems.db` — authoritative SQLite database
- `groups/` — pending one-use distribution source files
- `groups/completed/` — completed campaign markers/source files
- `groups/cancelled/` — cancelled campaign markers/source files

The database is authoritative for definitions, instances, observations, queued deliveries, update/removal work, campaigns, recipients, audit data, anomalies, and deleted-definition markers. Do not repair those states by hand unless the documented recovery procedure explicitly calls for database-level inspection.

## Installation

1. Stop the server cleanly.
2. Confirm Java 21 and the intended Paper/Leaf build.
3. Place the shaded EnthusiaLoreItems jar in `plugins/`.
4. Start the server.
5. Confirm `plugins/EnthusiaLoreItems/` exists and startup logs show storage initialization.
6. Confirm the plugin reaches read/write service state before exercising administrative mutations.
7. Confirm the two one-use distribution directories and marker directories exist before using mass distributions.
8. Record the server build, Java version, plugin version, jar SHA-256, and schema version in the deployment record.

If startup reports degraded/read-only or unavailable state, do not run create, give, edit, destructive, or distribution-start operations until the cause is understood.

## Configuration

Default configuration:

```yaml
database-busy-timeout-millis: 5000
database-queue-capacity: 256
database-shutdown-timeout-seconds: 10

delivery-claim-batch-size: 32
delivery-claim-lease-seconds: 30
duplicate-warning-interval-seconds: 300
default-page-size: 45
max-page-size: 100
mutation-budget-per-tick: 16
shared-containers-allowed: true
```

`shared-containers-allowed: true` is the default and leaves nested shulker/bundle storage unrestricted. Set it to `false` to prohibit players from inserting LoreItems into either shulkers or bundles; removing an existing LoreItem from those containers remains allowed.

### Startup-only settings

These require a full server restart after change:

- `database-busy-timeout-millis`
- `database-queue-capacity`
- `database-shutdown-timeout-seconds`

### Reloadable settings

These are loaded as one validated configuration snapshot and must not partially replace the active configuration:

- `delivery-claim-batch-size`
- `delivery-claim-lease-seconds`
- `duplicate-warning-interval-seconds`
- `default-page-size`
- `max-page-size`
- `mutation-budget-per-tick`
- `shared-containers-allowed`

A failed reload must leave the previous complete snapshot active. Reload must not discard active deliveries, mutations, campaigns, or destructive work. If the installed build does not expose a documented operator-facing reload action for the desired setting, use a clean restart instead of relying on Bukkit `/reload`.

## Permissions

Administrative permissions are intentionally split by capability:

- `enthusia.loreitems.admin.create`
- `enthusia.loreitems.admin.adopt`
- `enthusia.loreitems.admin.give`
- `enthusia.loreitems.admin.audit`
- `enthusia.loreitems.admin.edit`
- `enthusia.loreitems.admin.remove`
- `enthusia.loreitems.admin.purge`
- `enthusia.loreitems.admin.delete`
- `enthusia.loreitems.admin.destructive.inspect`
- `enthusia.loreitems.admin.destructive.control`
- `enthusia.loreitems.admin.destructive.review`
- `enthusia.loreitems.admin.distribution.inspect`
- `enthusia.loreitems.admin.distribution.start`
- `enthusia.loreitems.admin.distribution.control`

Defaults are operator-only. Grant the narrowest permission set required for a staff role. In particular, do not bundle delete/purge/review permissions into ordinary browse roles.

## Command surfaces

Primary administration:

```text
/loreitems create|adopt|give|browse|anomalies|audit|recovery|remove|purge|delete|operations|targets|destructive-metrics|pause-operation|resume-operation|resolve-removal ...
```

Mass distributions:

```text
/loredistribution reload|inspect|preview|confirm|campaigns|status|recipients|pause|resume|cancel|reconcile ...
```

Use command tab completion and the administrative GUIs for the installed build's exact arguments. Destructive actions require the build's preview/confirmation path; do not bypass confirmation by directly editing the database.

## Operational invariants

Operators should treat these as release-blocking safety properties:

- full inventories never cause lore items to drop as overflow;
- offline/full-inventory delivery remains durable until completion or explicit cancellation;
- inaccessible chunks/inventories are not force-loaded for update/removal;
- ambiguous physical side effects are surfaced for review instead of guessed/repeated;
- duplicate instance UUID copies are preserved until staff intentionally resolves them;
- malformed stacks are preserved and flagged rather than silently split or deleted;
- a deleted definition remains hidden from normal browsing while enough tombstone identity remains to remove late copies;
- completed/cancelled campaign state is database-authoritative and must not be recreated by copying an old group file;
- no SQLite or filesystem I/O is expected on the server thread;
- queues, result pages, retries, and per-tick mutation work are bounded.

If an observed condition contradicts one of these invariants, stop the affected operation and collect incident evidence before attempting repair.

## Metrics and backlog interpretation

The plugin exposes bounded-work and destructive/campaign status through administrative status/metrics surfaces. During an incident or acceptance run, capture at minimum:

- database queue depth/high-water mark and rejection/defer evidence;
- delivery/update/removal/destructive/campaign pending counts;
- `REVIEW_REQUIRED` or anomaly counts;
- active versus paused operation/campaign state;
- page/query limits in effect;
- configured per-tick mutation budget;
- relevant timestamps and operation/campaign identifiers.

A growing backlog is not by itself data loss. A queue exceeding configured capacity, silent disappearance of pending work, false success, or repeated ambiguous mutation is a defect and should be treated as an incident.

## Backups

### Preferred offline backup

Use an offline backup for release changes, schema upgrades, rollback points, and acceptance evidence.

1. Stop the server cleanly and wait for shutdown to complete.
2. Confirm the process has exited.
3. Copy the entire `plugins/EnthusiaLoreItems/` directory, including `loreitems.db`, `config.yml`, and all `groups/` marker/source directories.
4. Hash at least `loreitems.db`, the deployed jar, and the backup archive/directory manifest.
5. Record plugin version, jar SHA-256, database schema version, server build, Java version, and timestamp.
6. Preserve the backup until the deployment/acceptance window is closed.

### Online backup constraints

Do **not** copy only `loreitems.db` while the server is running and assume the copy is transactionally complete. SQLite runs in WAL mode, so live state can span the main database and WAL-related files. For a release/rollback backup, prefer the offline procedure.

If an online backup is operationally unavoidable, use a SQLite-supported consistent backup mechanism against the live database and verify the resulting copy with an integrity check before treating it as recoverable. A naive filesystem copy is not release evidence.

## Upgrade procedure

1. Complete an offline backup.
2. Record the current plugin version, jar SHA-256, schema version, pending queue counts, active campaigns, active destructive/update operations, anomaly/review counts, and database hash.
3. Replace the jar with the intended release-candidate/production artifact whose checksum matches the GitHub release asset.
4. Start the server and wait for storage initialization/migrations to finish.
5. Confirm the database reaches read/write service state.
6. Confirm schema migration success and run/record an integrity check.
7. Verify definitions, representative instance identities, deleted markers, pending work, campaign counts, and recent audit history still exist.
8. Verify active work resumes without duplicate delivery or repeated destructive side effects.
9. Run the staged smoke subset before restoring normal staff access.

Never downgrade a live database by simply swapping jars unless that exact rollback path has been rehearsed against a backup.
