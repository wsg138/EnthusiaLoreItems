# Worker 5 independent audit and evidence gate — 2026-10-12

Status: **IN PROGRESS; NOT RELEASE-READY**. Parent #83, worker #88.
Repository baseline: main 86999c032eaf658d6e66223d73aedf58cbd6cee2.
No production mutation, deployment, or automatic merge is authorized.

## Boundaries

- Worker A (#84): physical tracking queue and natural chunk lifecycle.
- Worker B (#85): exact cursor/player/container movement.
- Worker C (#86): hopper/nested inventory/anomaly/template event work.
- Worker D (#87): persistence, identity and rollback.
- Worker E (#88): independent tests, measurements, integrated candidate and review. No concurrent edits to A–D owned classes.

Initial draft heads (all open/unmerged when checked):
- #79 81dd6b0e3c394d9feb8a6badebe76dd536b0b727 (non-snapshot block references)
- #80 47f57cff2354e0f9f45f3aff40084812b30a78e6 (scan failures/peak timings)
- #81 88248ab2f62de8bf577d92b6e639daa98649c062 (previous integration)
- #82 e8a5d498183bf8c143669332e009130060b6605f (hopper early return)

#81 source review: scanChunkContainers calls chunk.getTileEntities(false),
reads holder.getInventory once, and passes the same Inventory to capture
and the scanner; PaperChunkContainerSingleInventoryReadTest asserts exactly
one read. This is inspection, not proof that the test passed on this head.
A's future changes to this method need the regression retested.

#82 source review: PaperHopperMoveObservation skips BOTH inventory snapshots
and the delayed reconciliation when the moved item has no identity evidence.
The separate identity-anomaly and template-update event listeners still run.
Do not claim a plugin-wide hopper cost reduction from this one fast path.

## Main-thread scheduler/hot path audit (source facts, not timings)

1. PaperPhysicalTrackingListener.start calls drain each tick and
   seedLoadedChunks every 100 ticks. It uses World.getLoadedChunks and iterates
   loaded entities/tile inventories. Worker A owns replacing this.
2. PaperEntityTemplateUpdateListener schedules controller.drain EACH tick.
   PaperEntityTemplateUpdateController has RESCAN_PERIOD_TICKS = 100; after
   a completed full loaded-entity sweep, it starts a new sweep after 100
   ticks, calling world.getLoadedChunks and chunk.getEntities. This is a
   **separate** recurring global loaded-chunk traversal even if physical
   tracking seeding disappears. Measure before editing; coordinate with C.
3. PaperTemplateUpdateListener invokes PaperTemplateUpdateAccessController
   every tick. wakeAccessible starts a PaperLoadedContainerWalker discovery.
   Underlying walker reads loaded chunks and chunk.getTileEntities(false).
   Determine if wake/rescan can repeat needlessly under template changes.
4. PaperDistributionRecipientBindingWorker has a per-tick poll task,
   configured bounded queue and periodic online-player scan.
5. Anomaly warnings, distribution delivery/recovery, template revision
   planner, mutation recovery, GUI/session timeouts add independent
   scheduled work; measure active and idle paths separately.
6. Physical, template and anomaly listeners may all handle hopper/GUI
   events. Never remove correctness/security handlers based on unmeasured
   duplicated work.

Loaded-chunk iteration is NOT forced chunk loading. A getChunkAt is only
acceptable after a server-thread loaded guard; the current tracking
request checks isChunkLoaded before getChunkAt. Re-evaluate final branch.
None of these source observations alone prove cause of SMP slow ticks.

## Mandatory adversarial acceptance (all unverified until exercised)

The machine-readable gate requires 30 named test paths (see
REQUIRED_WORKFLOWS in tools/verify_event_first_evidence.py):
- Cursor to player slot, slot to cursor; shift click, drag multi-slot,
  hotbar-number swap, double-click collect, offhand, armor, ender chest,
  double chest
- Hopper, nested shulker, nested bundle, drop/pickup, item frame,
  canceled event, creative copy, duplicate identity, malformed PDC
- Chunk unload/reload, 1,000 repeated same-chunk load events, 1,000
  distinct chunk loads beyond queue capacity, important player admission
  overload, restart/rejoin, SQLite WAL failure and restore
- Authorization denial, 10k loaded chunks, 601-piglin confound fixture,
  shutdown drain and continuation after partial scan

For each test record actual post-event slot, cursor, nested path and
presence/authority; no UNIQUE after truncated/incomplete evidence, no
forced chunk load, no permanently lost important admissions, no SQL
data loss. Probe load-generation races across world UUIDs. If a test
or full build is skipped, preserve explicit BLOCKED status; never
substitute a mock result for real disposable Paper acceptance.

## Benchmark contract

Compare matched AI-OFF baseline and candidate runs on disposable
Paper 26.2 / Java 25. Record exact source/JAR SHAs, UTC timestamps,
hardware and CPU contention, world seed, duration/warmup, players,
loaded chunks, zombified piglins, hopper move count, nested structures.
Use 1k–10k ordinary hopper transfers, 1,000 duplicate/distinct loads,
20-player inventory mix and 10k warm loaded-chunk scenario. Measure
p50/p95/p99/max MSPT, main-thread callback and tick-drain wall-time,
alloc/GC, queue peak and p95/max admission age, rejected/retried/
recovered/unresolved counts, dirty backlog, full inventory scan counts.
Report fixture mismatch as BLOCKED, not a speedup; synthetic timings
are not production performance evidence.

## Offline evidence validator

    python -m unittest discover -s tools -p test_verify_event_first_evidence.py
    python tools/verify_event_first_evidence.py actual-staging-evidence.json

This validator **does not run the workflows**. Its passing unit-test
fixture is synthetic and must NOT be used for a release claim.

Actual evidence JSON must include:
- merged=false and production_touched=false
- provenance: source_sha and staging_source_sha (matching 40 lowercase
  hex); built_jar_sha256 and staging_loaded_jar_sha256 (matching inner
  JAR 64 lowercase hex); artifact_evidence log/link
- gates mapping: keys clean_build, unit_tests, lint_and_complexity,
  codacy_zero_new, independent_review, java25_paper26_2,
  wal_backup_restore, rollback_rehearsal; each has status PASS and
  a real evidence log/link
- workflows mapping: each of the 30 required workflow names; each
  has status PASS and evidence link or log
- benchmark.baseline and benchmark.candidate: same MATCH_FIELDS from
  validator, Paper 26.2, Java major 25, AI state OFF, real evidence
  link/log, finite monotonic nonnegative mspt_p50/p95/p99/max
- benchmark.candidate: measured zero forced_chunk_loads,
  unresolved_important_observations, dirty_backlog_after_drain,
  ordinary_hopper_full_inventory_scans, false_unique_after_partial_scan;
  plus actual queue_rejection_recovery_evidence

The tool rejects mismatched measurement fixtures, p95/p99 regressions,
missing workflows, unproved gates, absent provenance and false-success
counters. Even a green offline contract is NOT proof that submitted
evidence is authentic, and never authorizes production deployment.

## Integration and stop conditions

1. Wait for A–D handoff with independently reviewed draft PR and exact SHA.
   Refresh main/PR heads; resolve any overlapping #79/#80/#81/#82 changes
   with code-owner review, not blind cherry-picking.
2. Build fresh integration branch. Rerun clean Gradle suite, changed-code
   complexity/lint and hosted Codacy. CodeRabbit failure/rate-limit means
   no automated approval; arrange independent review.
3. Run all 30 workflows on a disposable Paper/Java25 server.
   Rehearse SQLite WAL-consistent backup, restore, crash and rollback
   without touching production.
4. Hash built INNER JAR, staging LOADED INNER JAR, and source manifest.
   Artifact ZIP hash cannot replace binary verification.
5. Compare matched AI-OFF benchmarks and run evidence validator.
   Any failed/skipped gate, uncaught queue admission loss, false UNIQUE,
   SQL replay problem, unavailable staging hash or unknown deployed
   binary is a **no-merge** blocker; submit draft RC for owner decision.

Unverified at this writing: final A–D code, Java25 Paper acceptance,
zero-new Codacy findings, external code review, real benchmark,
WAL restore rehearsal, loaded staging JAR and owner release approval.
