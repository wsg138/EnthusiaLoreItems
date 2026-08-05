# WP-05 — Live acceptance and release

## Objective

Process complete manual Paper/Leaf acceptance evidence for the WP-04 release candidate, fix every confirmed defect in the same fixed package, rerun affected and regression cases, and publish the production EnthusiaLoreItems release.

## Dependencies

- WP-04 is `COMPLETE`.
- GitHub prerelease `v1.0.0-rc.1` and its checksum/artifacts point to the verified WP-04 merge commit.
- A designated live acceptance server can run Java 21 and the target Paper/Leaf 1.21.11-compatible build with Geyser and Floodgate.

## Complete required scope

1. Execute every case ID in the WP-04 manual checklist against the exact RC jar. Evidence for each case must record server implementation/build, Java version, plugin version, jar SHA-256, schema version, configuration, test accounts, timestamps, precise steps, expected result, actual result, relevant logs/database queries/metrics, cleanup, and PASS/FAIL/BLOCKED status.
2. Cover at minimum:
   - Java and `*`-prefixed Bedrock identity and commands/GUI;
   - create, adopt, self/online/offline give, full inventory, join delivery, and restart recovery;
   - all WP-01 editor fields, replace-from-held uncommon components, preview/cancel, rollout to online/offline/container/nested/entity/display instances;
   - player inventory/armor/offhand/cursor, Ender Chest, physical containers, hopper-observable movement, shulkers, bundles, dropped entities, item frames/glow frames, armor stands, death drops, chunk unload/reload, and confirmed-now versus last-confirmed display;
   - fire/lava/explosion/cactus/despawn/durability/conversion protection, mob pickup prevention, and intentional void loss;
   - duplicate copies, malformed stacks, five-minute warnings, anomaly inspection/resolution, and ambiguous mutation review;
   - exact removal, purge, full delete, pause/resume, restart during each destructive/update phase, and a late copy returning from an unloaded chunk/offline inventory/backup simulation;
   - group-file validation, duplicate start, active/completed/cancelled markers, Java/Bedrock/UUID recipients, unresolved first join, full inventory, pause/resume/cancel, restart, and exactly-once delivery;
   - API durable outcomes and idempotency replay using a test consumer;
   - reload with active work, shutdown under queued load, degraded/read-only startup, backup/restore, rollback, queue saturation metrics, and 100-player-equivalent staged load.
3. Store the acceptance result index and redacted evidence in GitHub. Large binary/raw evidence may use GitHub Actions artifacts or release/issue attachments, but the committed index must identify permanent URLs, hashes, case IDs, and retention; a local report or chat statement is not evidence.
4. Treat every reproducible mismatch from requirements, architecture, package acceptance, data safety, or operator documentation as a confirmed defect. Record severity, reproduction, affected case IDs, root cause, and fix commit in the WP-05 PR or linked GitHub issue.
5. Fix every confirmed defect within WP-05. Add an automated regression test for every defect. When the defect depends on live server behavior that the repository test harness cannot instantiate, add a named permanent manual regression case with exact setup/assertions and a committed technical explanation identifying the unavailable API/event behavior; this documented exception is the only substitute for an automated test. Do not defer a confirmed defect to a follow-up package or release known defects as accepted limitations.
6. After each fix, rerun the exact failed case, every case sharing the affected state machine/adapter, and the full safety regression subset for delivery, update, delete, campaign, reload, shutdown, and data integrity. Evidence must identify the fixed head/jar SHA.
7. Repeat the complete matrix on the final release-candidate build after the last code change. All cases must be PASS; `BLOCKED`, waived, not-run, and unsupported claims are not release approval.
8. Re-run full automated CI, exact-head Codacy, packaging, migrations, failure matrix, and profile thresholds on the final WP-05 head.
9. Finalize version `1.0.0`, release notes, upgrade/backup/rollback checklist, checksums, CycloneDX JSON SBOM, Gradle dependency manifest, and operator sign-off. Verify a clean upgrade from the RC database and a rollback rehearsal using the documented backup.
10. Merge WP-05 with a normal merge commit. After live `main` verification and successful main checks, create tag/release `v1.0.0` from that merge commit with shaded jar, SHA-256 checksum, CycloneDX JSON SBOM, Gradle dependency manifest, release notes, acceptance index, and rollback instructions. Verify all assets and tag target.

## Exact acceptance criteria

- Every required manual case has complete GitHub-backed evidence for the exact tested jar SHA and final result PASS.
- Every confirmed defect is fixed, linked to a commit, covered by regression evidence, and retested; zero confirmed defects remain open.
- Final database integrity, pending queues, campaign counts, instance identities, deleted markers, and audit history match expected results after restart, upgrade, restore, and rollback rehearsal.
- No live case observes item loss, duplicate delivery, wrong-target deletion, force loading, main-thread I/O, unbounded backlog, unsafe reload/shutdown, or misleading current-location claims.
- Automated checks and profile thresholds pass on the exact final head and post-merge main.
- Production release `v1.0.0` targets the verified merge commit and contains all required artifacts with matching checksums.

## Required automated tests

- An automated regression test for every confirmed defect, except only the specifically documented live-server-harness exception defined in required scope; each exception requires a permanent named manual regression case.
- Full test suite and all WP-04 failure/saturation/migration/profile/API/package checks after the last fix.
- Clean-install, RC-to-final upgrade, restart recovery, backup restore, and rollback compatibility automation.
- Artifact/version/tag/checksum validation.
- Exact-head GitHub Actions, repository tooling, architecture, complexity, static analysis, and Codacy.

## Required review and verification gates

- Independent review of all code fixes and a separate evidence audit confirming every case ID, jar SHA, expected/actual result, and permanent evidence reference.
- Release review explicitly covers item loss, duplicate creation, wrong-target destruction, main-thread work, bounds, reload/shutdown, migration/rollback, Geyser/Floodgate, API, and unsupported claims.
- No requested changes, zero unresolved threads, all exact-head checks successful.
- Owner/operator sign-off recorded on GitHub after the final full matrix.
- Normal merge commit, verified main checks, and verified `v1.0.0` tag/release assets.

## Explicit exclusions

- Waiving or deferring a confirmed defect.
- Treating an unexecuted, blocked, or screenshot-only case as PASS.
- EnthusiaTags integration; WP-06 begins only after this release.
- Reward-balance changes.
- Starting WP-06.

## Definition of complete

WP-05 is complete only when the final full live matrix is all PASS, every confirmed defect is fixed and retested, all automated/review gates pass, the PR is normally merged, live `main` is verified, `v1.0.0` is published from that merge with correct artifacts, durable state is updated, and the worker stops.

## Expected status transitions

`BLOCKED -> READY -> IN_PROGRESS -> EVIDENCE_PENDING -> IN_PROGRESS (for any defect) -> IN_REVIEW -> VERIFYING -> MERGED -> COMPLETE`

Any failed case returns to `IN_PROGRESS` within WP-05. The package remains `MERGED` until the production release is verified.

## Branch and PR naming

- Branch: `agent/wp-05-live-acceptance-release`
- PR title: `WP-05: complete live acceptance and release LoreItems`

## Exact next package

WP-06 — EnthusiaTags service-API integration. It remains blocked until `v1.0.0` is verified and requires a separate assignment.
