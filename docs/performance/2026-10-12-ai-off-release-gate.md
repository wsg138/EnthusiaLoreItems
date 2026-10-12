# LoreItems release gate and AI-OFF evidence capture — 2026-10-12

**Purpose:** A non-deployment runbook for [LoreItems #71](https://github.com/wsg138/EnthusiaLoreItems/issues/71), queue saturation [#39](https://github.com/wsg138/EnthusiaLoreItems/issues/39), binary provenance [#54](https://github.com/wsg138/EnthusiaLoreItems/issues/54), and the parent [EnthusiaAI #119](https://github.com/wsg138/Enthusia-AI/issues/119).

**Authorization boundary:** This document does not approve or execute a live operation. No live SFTP read/write, service start/stop, player kick, JAR replacement, SQLite change, affinity change, AI inference/stress test, or rollout is authorized by creating/merging this documentation. An owner-approved, operation-specific maintenance window is required for each live change.

## 1. Exact observed repository checkpoint (2026-10-12)

| Item | Verified reference / caveat |
| --- | --- |
| LoreItems `main` | `86999c032eaf658d6e66223d73aedf58cbd6cee2` (merge of [#74](https://github.com/wsg138/EnthusiaLoreItems/pull/74)) |
| Earlier improvements | [#72](https://github.com/wsg138/EnthusiaLoreItems/pull/72) and [#73](https://github.com/wsg138/EnthusiaLoreItems/pull/73) merged; no verified live deployment |
| Exact-main CI | [push run 38264369891](https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38264369891), successful |
| Exact-main plugin artifact | [artifact 11754495620](https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38264369891/artifacts/11754495620), `enthusialoreitems-plugin`; **JAR SHA256 and JAR byte count not yet independently extracted/verified** |
| Artifact ZIP | GitHub lists archive SHA256 `3058798cc4b88b3477b6af5b4d9066ee56da750db53143d51ddc951c27ac582b`; **not the inner JAR checksum** |
| Release flow | [Release RC run 38264569382](https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38264569382) succeeded; [Release run 38264569702](https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38264569702) failed; no publication approval inferred |
| Unmerged candidates | [#70](https://github.com/wsg138/EnthusiaLoreItems/pull/70) non-snapshot reads; [#75](https://github.com/wsg138/EnthusiaLoreItems/pull/75) scan peak timings; [#76](https://github.com/wsg138/EnthusiaLoreItems/pull/76) exception containment; [#77](https://github.com/wsg138/EnthusiaLoreItems/pull/77) overload characterization |
| Exact-head check snapshot | #70, #75, #77: 27/27 GitHub check runs passed; #76: 24 passed/4 failed, including `verify`, `editor-contract`, `revision-rollout`, and an unrelated Sentinel toolchain-acquisition check. These are observations, not approvals. |
| Live JAR | **UNVERIFIED for current SMP process**. The historical October 9 artifact `5c7fceebc723e5d9293e45fc689528effc682e9a1433afb24c93ff7b22a17580` matched CI from PR #41 but must not be assumed still loaded. |

Do not combine an arbitrary PR branch artifact with this `main` checkpoint. A new merged commit requires a new exact-head manifest, CI results and inner-JAR checksum.

## 2. Release blocker matrix

1. **Identity and storage:** replay full delivery/late-copy/duplicate-identity/ambiguous-mutation, nested shulker/bundle/ender-chest, full-inventory, disconnect/rejoin, world/chunk unload, LAST_CONFIRMED and persistence-failure cases. [#45](https://github.com/wsg138/EnthusiaLoreItems/issues/45) has evidence that teleport precedes actual chunk unload by 2–4 seconds on successful tests; the intermittent failure's cause remains unproved.
2. **Queue admission:** a historically full queue (512 peak, 871 nonperiodic + 32 periodic rejected) is not repaired by #73's new player/chunk counters, #75's timers, or #77's reproduction. [#77](https://github.com/wsg138/EnthusiaLoreItems/pull/77) proves 136 unique chunk-load requests at capacity 128 reject eight without automatic replay. Explicitly distinguish *accepted*, *rejected*, *dequeued*, *returned without exception*, *failed*, *inaccessible/skipped*, and *physical evidence actually observed*. No losslessness claim without later authoritative reconciliation proof.
3. **Queued exception handling:** #76 currently fails existing test expectations for `routineReconciliationMakesProgressDuringEventFlood` and `oneTickDrainCapturesEventAndRoutineCostWithoutChangingAdmissions` (`scanPeriodicCompleted` was expected 1 but became 0). Investigate a hidden chunk-scan exception versus MockBukkit fixture limitation; do not just change expected counts. Non-throwing offline-player/unloaded-chunk skips must never be presented as verified observations.
4. **Non-snapshot semantics:** #70 reports strong operation-level microbenchmarks, **not production TPS improvements**. Audit `getHolder(false)` / `getState(false)` against entity/player/ender/double-container references, loaded-chunk boundaries, delayed moves and stale state, and all merged #72–#74 changes. The distinct Bloom TEST JAR contains additional classes absent from `main`; never overwrite that JAR to test #70 until feature parity is independently proved.
5. **Integration:** land only reviewed, conflict-free PRs after exact-head clean build, all 25 WP-05 acceptance workflows, hosted Codacy zero *new valid* findings, independent review, and disposable real-Paper tests. A self-review is not an independent approval. No feature should proceed solely to hit a deployment date.

## 3. Exact binary manifest and compatibility gate (offline first)

For the eventual **frozen candidate commit**, preserve:
- Commit SHA + PR merge IDs; CI/workflow URL, passed/failed/skipped step details; runner OS/JDK/Gradle and Paper build.
- Artifact ID/URL, **extracted** `build/libs/EnthusiaLoreItems.jar` SHA256 and byte count (never substitute archive ZIP digest); `plugin.yml`, manifest/build identifier and complete SQL migration resource comparison.
- Historical deployed JAR and exact *current* SMP pathname, file count, SHA256, byte count, plugin class/manifest metadata. Prove **one active .jar**, not two copies under different names. Preserve the current binary as the rollback binary.
- Compare candidate versus historical/current `plugins/EnthusiaLoreItems/config.yml`, data schema/migration lineage, immutable PDC item identifiers, `groups/` completed/cancelled markers and active distribution/mutation queues. Identify any changed defaults and changes to data semantics. A matched `plugin.yml` version does not prove class equivalence or database downgrade compatibility.

**No production read access?** Leave the live hash, schema and active JAR count explicitly **UNVERIFIED**; never backfill from a historical snapshot or an unrelated TEST server.

## 4. Backup / staging / rollback (requires separate maintenance authorization)

1. **Before maintenance:** capture owner permission, maintenance window, exact selected JAR SHA, backup target and available restore tooling. Confirm no untrusted new changes, ongoing deliveries, destructive operations or distributions. Use a disposable Paper environment to rehearse a restore from representative sanitized DB/config/group state; confirm integrity and startup read/write behavior.
2. **Preferred consistent backup:** cleanly stop the SMP at the *approved* window, wait for Java to exit and writers to quiesce, then copy the **whole** `plugins/EnthusiaLoreItems/` directory including `loreitems.db`, any WAL/SHM companions, configs, and `groups/` state/markers. Copy the **original active JAR** separately. Include checksums, file inventory, ownership and timestamps. Do not copy only the `.db` while SQLite is in WAL mode. A separately approved SQLite online-backup API can produce a consistent database image only if the associated plugin group/marker state and point-in-time consistency are also addressed.
3. **Proof of restorability:** run SQLite `PRAGMA integrity_check` on the *backup copy*, record result, schema version, table/count reconciliation and file hashes, and demonstrate a disposable restore. The backup should be immutable and kept outside the overwrite path.
4. **Controlled staging:** ensure exactly one old active JAR is removed from the active plugin scan path and one intended candidate JAR is placed with exact hash validation. Stage/rename on the same filesystem and verify file size + SHA256 remotely; avoid a second `.jar` that Paper might load. No restart until approved. All file writes require authenticated task-scoped transport with verified host key.
5. **Startup acceptance:** after approved restart, record actual loaded JAR path/hash and class/version metadata; verify SQL migrations, no startup errors, read/write service, stable identity, conflict and duplicate warnings, representative item operations, queue accounting, loaded chunk semantics, and bounded GC/main-thread costs. Halt and roll back on new identity loss, database errors, unexpected migration, duplicate loaded plugin or material TPS deterioration.
6. **Rollback:** stop the server; preserve the failed-state files for forensics; restore **matching** original JAR + complete database/config/group state from the same snapshot; revalidate integrity and identity. Do **not** downgrade just the JAR after a migration or roll back an older database over newly changed player/world item inventories without reconciling those inventories: this can reintroduce duplicate, missing, or stale item identities. Keep the server closed for review if a consistent restore point is unavailable.

## 5. AI-OFF SMP baseline packet — *only after a separately approved release*

Never run Qwen inference to collect this baseline. Tell the EnthusiaAI coordinator (issue [#119](https://github.com/wsg138/Enthusia-AI/issues/119)) whether a verified optimized JAR is actually loaded; if not, capture **pre-release comparison only**, not an optimized baseline.

After the verified release has started successfully:

- Record SMP process start time and current uptime, OS/build/JDK, **actual loaded JAR SHA256**, LoreItems config budget, other plugin versions, loaded chunk/entity/player counts and active mob/hopper farm regions. Note whether the previously concentrated nether piglin farm is loaded; compare similar activity.
- Capture two screenshots or structured exports of **the same LoreItems diagnostics process** 3–5 minutes apart with wall-clock timestamps: synchronous hopper move count / total nanos / peak; deferred reconciliation count / total / peak; queued event/periodic backlog and peak; per-player versus per-chunk versus periodic rejection counters; accepted/dequeued/completed/failed/skipped execution counters (where available), truncated scans, coalesced player and periodic requests, persistence queue/in-flight and persistence failures.
- Calculate **deltas only for cumulative counters** during the *same server process*. Peaks are maxima, not interval sums; do not subtract or compare them as rate. A zero queue at capture time does not erase earlier admission losses; historical 871/32 rejects cannot be split by newly deployed source counters.
- Pair this with matching-duration server TPS and MSPT p50/p95/p99/max, CPU/GC if accessible, and **Java `Server thread`** Spark slow-tick profile for >100ms ticks, per Bloom's `/spark profiler --only-ticks-over 100 --timeout 300` request if supported by that installed Spark version. Preserve exact URLs, start/end times and tick sample counts.
- Report every divergence or missing metric explicitly. Distinguish on-main-thread hopper callback cost from deferred scans, non-LoreItems Minecraft block ticking, global pauses, world/farm load, and eventual database writes. Do not infer exclusive plugin milliseconds from overlapping Spark stack aggregates.
- Do not mark LoreItems as performance-repaired or AI co-location as safe until sustained *matched* AI-OFF evidence is credible, tracking integrity is verified, and the independent EnthusiaAI worker completes any separately authorized AI-ON comparison.

## 6. Acceptance decision table

| Condition | Meaning / next action |
| --- | --- |
| CI clean; artifact SHA known; **not live** | Offline candidate only; **no production TPS claim** |
| Release gate unapproved or WAL backup unproven | **Do not deploy** |
| Live hash differs or duplicate JAR present | Stop verification; investigate before changing anything |
| Exact live hash + backup + safe startup; AI-OFF evidence pending | Permit only normal observational capture in approved scope, **not inference** |
| AI-OFF scan rejects/failures increase or identity conflict occurs | Investigate and preserve evidence; no success declaration |
| Matching AI-OFF steady-state evidence + tracking integrity verified | Send evidence to parent AI worker for its independent benchmark decision |

**Owner-facing next action:** authorize separately (1) read-only current SMP provenance capture when secure task-scoped access exists, and (2) a later *specific* maintenance backup, replacement and restart window only after a frozen reviewed candidate passes all release gates.
