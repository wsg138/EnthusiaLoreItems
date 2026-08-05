# WP-04 — Automated production hardening and release candidate

## Objective

Close automated reliability, upgrade, performance, operational-documentation, static-analysis, and stable-API gaps across the complete LoreItems system, then produce a reproducible release candidate for manual live acceptance. WP-04 does not claim live-server approval.

## Dependencies

- WP-03 is `COMPLETE` on live `main`.
- Every functional requirement through campaigns is implemented and available for system-level hardening.

## Complete required scope

1. Build a deterministic failure-injection harness and cover every durable state machine: definition creation, adoption, direct/API delivery, template revision/update, exact removal, purge, full delete/late copy, duplicate resolution, campaign/recipient, and audit/current-state projection.
2. For each state machine, inject failure or restart before intent commit, after intent/before claim, after claim/before Paper apply, after apply/before verification persistence, during verification commit, after terminal commit, during reload, and during shutdown. Assert idempotent recovery or explicit `REVIEW_REQUIRED`; never guess or repeat an ambiguous physical side effect.
3. Add queue saturation/backpressure tests for database executor, delivery, update, destructive, campaign, notification, reconciliation, GUI query, debounce, cache, and retry queues. Assert configured capacity, rejection/defer behavior, bounded retries, bounded result pages, and useful metrics.
4. Add migration/upgrade coverage from every committed schema version to the release-candidate schema, including populated legacy states, interrupted migration rollback, foreign-key/integrity checks, WAL/busy-timeout settings, index presence, and no loss of identity, audit, pending work, or deleted markers.
5. Add reload/shutdown tests proving intake stops safely, only bounded work drains, pending work persists, executors close, the service becomes unavailable when required, configuration swaps atomically, and active campaigns/operations survive.
6. Add API contract tests and finalize the stable versioned Bukkit service used by EnthusiaTags. Verify durable outcomes, external idempotency-key replay, unknown definition, read-only/unavailable, validation failure, plugin reload, and restart. Do not add command dispatch as an integration boundary.
7. Add a reproducible performance/profile harness and committed results for these fixed scenarios:
   - 100 simulated online players and 25,000 tracked instances;
   - 5,000 naturally loaded container/display scopes with nested-item samples;
   - 10,000 pending mixed update/removal/delivery mutations;
   - 10 active campaigns of 2,000 recipients each with mixed resolved/offline/full/unresolved states;
   - 100 simultaneous paginated administrative queries.
   The harness must prove no SQLite/filesystem I/O or retained live Bukkit object crosses onto async/main-thread boundaries incorrectly, no per-tick configured budget is exceeded, no unbounded structure grows with total history, and queue/latency/rate metrics are emitted. Record environment, configuration, dataset generator, elapsed time, throughput, queue high-water marks, p50/p95/p99 database latency, and p50/p95/p99 measured main-thread task duration. A scenario fails if any main-thread task exceeds 50 ms, p99 exceeds 10 ms, a configured queue exceeds capacity, work is lost, or the harness cannot reproduce its dataset/results.
8. Run and remediate static analysis and complexity without broad suppressions. Every suppression must be narrow, justified beside the code, and documented. Keep Codacy at the repository's required quality level.
9. Complete operator documentation: installation/upgrade, configuration, permissions, commands/GUI, metrics, backups, online/offline backup constraints, restore, integrity check, degraded/read-only recovery, queue/review recovery, deleted-marker handling, campaign marker repair, staged deployment, rollback, and incident collection.
10. Commit a complete manual acceptance checklist for WP-05, with unique case IDs, prerequisites, exact steps, expected durable/database/physical result, evidence required, cleanup, and rollback for the full matrix in WP-05.
11. Set the release-candidate version to `1.0.0-rc.1`, generate the shaded jar, CycloneDX JSON SBOM, Gradle dependency manifest, SHA-256 checksum, test reports, and release notes from an exact commit. Configure or use GitHub Actions so the exact verified merge commit can publish a GitHub prerelease `v1.0.0-rc.1` with those artifacts.
12. After the normal WP-04 merge and exact `main` verification, publish/verify the `v1.0.0-rc.1` prerelease from that merge commit. This is WP-04 finalization, not permission to begin WP-05.

## Exact acceptance criteria

- Every listed state-machine failure point has an automated test with a deterministic expected recovery state.
- Saturation cannot create unbounded memory growth, silent work loss, false success, or main-thread I/O.
- Every historical schema migrates to the RC schema with integrity and identity preserved.
- Stable service API behavior is versioned, documented, and tested for replay/reload/restart.
- All fixed profile scenarios meet every stated pass threshold and committed results identify the exact code/config/environment.
- Full CI and exact-head Codacy are clean without broad suppression or unsupported claims.
- Operator/recovery/rollback docs and the WP-05 case matrix are complete enough to execute without inventing steps.
- GitHub prerelease `v1.0.0-rc.1` points to the verified WP-04 merge commit and contains the shaded jar, checksum, CycloneDX JSON SBOM, Gradle dependency manifest, and release notes.
- No document calls the RC production-approved or claims live Paper/Leaf behavior was tested.

## Required automated tests

All tests in required scope are mandatory, plus:

- repeated randomized/stateful recovery runs with fixed seeds and stored failing-seed output;
- API binary/source compatibility check for the stable public API surface;
- packaging smoke test that loads plugin metadata and verifies the shaded jar contains required modules/migrations/defaults;
- artifact checksum verification plus either bit-for-bit reproducible shaded jars from two clean builds or, when ZIP timestamps/signatures prevent byte identity, an automated normalized-entry manifest comparison that fails on any content difference;
- full `gradle --no-daemon clean check`, `:plugin:shadowJar`, tooling tests, architecture tests, complexity, static analysis, and exact-head Codacy.

## Required review and verification gates

- Independent system review across item loss, duplication, ambiguity, threading, bounds, migrations, reload/shutdown, API, packaging, rollback, and evidence accuracy.
- Reviewer verifies every profile threshold against committed raw/result data and every failure matrix row against a test.
- Exact-head PR checks pass after the final change; no requested changes or unresolved threads.
- Normal merge commit; live `main` exact checks pass.
- RC tag/release and assets are verified against the merge SHA and checksums before WP-04 becomes `COMPLETE`.

## Explicit exclusions

- Executing or claiming manual live-server acceptance; WP-05.
- Fixing hypothetical behavior without a requirement, test, profile result, review finding, or reproducible defect.
- EnthusiaTags integration; WP-06.
- Production `v1.0.0` release.
- Starting WP-05.

## Definition of complete

WP-04 is complete only when all automated hardening, performance, migration, API, packaging, documentation, and review criteria pass; the PR is normally merged and `main` verified; `v1.0.0-rc.1` is published from that merge with required artifacts; durable state is updated; and the worker stops.

## Expected status transitions

`BLOCKED -> READY -> IN_PROGRESS -> IN_REVIEW -> VERIFYING -> MERGED -> COMPLETE`

The package remains `MERGED` rather than `COMPLETE` until RC publication/verification succeeds. Failures continue WP-04.

## Branch and PR naming

- Branch: `agent/wp-04-production-hardening`
- PR title: `WP-04: harden production paths and produce release candidate`

## Exact next package

WP-05 — live acceptance, confirmed-defect remediation, and production release. It remains blocked until the RC is verified.
