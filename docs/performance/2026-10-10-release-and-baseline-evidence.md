# LoreItems performance evidence and rollout readiness — 2026-10-10

This is an analysis record, **not a deployment instruction**. The server has not been modified.

## Immutable release artifacts

| Role | Source SHA | JAR bytes | SHA-256 |
|---|---|---:|---|
| October 9 SMP snapshot baseline | `91a57261d2627c67e05d0a470a73c40734104889` (PR #41) | 13,240,936 | `5c7fceebc723e5d9293e45fc689528effc682e9a1433afb24c93ff7b22a17580` |
| Fully tested optimization candidate | `e50ae6128047c9ab6cc1deb54408c099774a7090` (PR #52) | 13,284,167 | `3b51979c12a2b0f325e754fbc4f66436cabaddbf57fd0bc48d9d3278a74a1852` |

The baseline checksum matches the SMP inventory snapshot, the local archived copy and the historical PR #41 CI JAR. Both archives declare internal plugin version **1.0.1**, regardless of the snapshot's external `1.0.5` filename.

Static archive comparison: **plugin.yml identical; all 11 migration SQL resources identical; 19 added classes, 43 modified classes, no removed LoreItems classes**. This does not prove behavioral/database compatibility. Candidate PR #52 passed **24 of 24** exact-head workflows.

## Historical Spark findings

Source: `wsg138/Enthusia-AI`, `deploy/bloom/SPARK-SMP-STACK-ROOT-CAUSE-REVIEW-20261010.md`, branch `fix/discord-command-safe-upsert-20261008`.

- Template scan/resolution path: about **8.96 seconds** inclusive in one reported slow-minute profile, including ~**5.20 seconds** inside block-state resolution.
- Deferred player physical scans and nested identity checks: ~**5.22 seconds** inclusive in that slow minute, including ~**3.82 seconds** inside block-state metadata handling.
- These are historical *inclusive sampling times*, not additive server CPU cost, not p95 MSPT and not measured improvement.
- Earlier very long ticks (>1 second) cannot be attributed exclusively to LoreItems from unfiltered samples.

## Normalized pre/post measurement schema

For each sampling window, record start/end UTC, candidate SHA, total seconds, online-player range, loaded chunks, hopper/container activity estimate, CPU contention / AI workload, Spark URL, MSPT median/p95/p99, TPS, queue gauge high-water mark, and counter deltas. Keep personal identifiers out of public evidence.

| Metric | Kind | Interpretation |
|---|---|---|
| Spark MSPT p50/p95/p99 | Per sample window | Main output; compare like-for-like demand |
| `tracking.scan_backlog` | Gauge | Whether scheduled scan queue saturates |
| `tracking.rejected` | Counter | Queue-pressure rejection, concerning if persistent |
| `tracking.scan_truncated` | Counter | Inventory traversal hit its bounded work cap |
| `tracking.deferred_player_scan_coalesced` | Counter | Repeated player requests avoided, expected to increase under bursts |
| `tracking.periodic_chunk_scan_coalesced` | Counter | Repeated periodic-chunk requests avoided |
| `tracking.deferred_player_scan_schedule_rejected` | Counter | Scheduler refused a deferred action; investigate |
| Duplicate/malformed identity and creative-copy warnings | Events | Security correctness check, not optimization score |
| Storage pending-operation/rollback counts | State | Durable state must survive reload and restart |

Counters are confirmed from PR #52 source; check actual metric exporter/dashboard availability before relying on them. These performance counters do **not** record the duration of each scan, so the next observability work should capture bounded, low-overhead, source-tagged timing only after review.

## Release gates

- [x] Historical snapshot baseline binary found and hashed.
- [x] Candidate exact JAR built and SHA-256 verified.
- [x] SQL migration resource and plugin descriptor diff reviewed.
- [x] Candidate 24/24 CI acceptance completed.
- [ ] Current active SMP remote JAR path and hash independently verified.
- [ ] Safe, consistent production SQLite database backup verified.
- [ ] Authorized, host-key-pinned SFTP staging interface available to this worker.
- [ ] Candidate remotely staged and its SHA-256 verified.
- [ ] Scheduled restart loads the new candidate successfully.
- [ ] Comparable Spark before/after evidence and identity/storage health review completed.

See [release issue #54](https://github.com/wsg138/EnthusiaLoreItems/issues/54), [performance issue #39](https://github.com/wsg138/EnthusiaLoreItems/issues/39), [combined PR #52](https://github.com/wsg138/EnthusiaLoreItems/pull/52) and [Blackboard SFTP PR #101](https://github.com/wsg138/Blackboard/pull/101).

**Current transport note:** this chat's Blackboard connection does not advertise SFTP operations. Blackboard PR #101 remains unmerged, with a reported per-worker authorization trust-boundary blocker. Do not work around the plugin's restricted command/credential boundary.
