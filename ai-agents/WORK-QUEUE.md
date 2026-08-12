# Fixed remaining-work queue

## Queue invariants
Exactly six immutable packages. Live GitHub outranks snapshots. Resume the single unfinished canonical lock before new work. Never split packages or begin the next package in the same completion chat.

| Order | Package | Weight | Status | Dependency / routing |
|---:|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | 20% | COMPLETE | normally merged and verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | 20% | COMPLETE | normally merged and verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | 20% | COMPLETE | normally merged and verified |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | 15% | COMPLETE | normally merged and verified; RC prerelease verified |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | 15% | COMPLETE | prospective final state in open PR #18; exact evidence head `8f221932...` passed full acceptance plus production startup/restart; global completion awaits final-head verification, merge and release finalization |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | 10% | READY | prospective unlock only; do not claim until WP-05's prospective state is merged and post-merge production `v1.0.0` is verified |

## Progress
- Prospective completed: 5/6 packages.
- Prospective weighted completed progress: 90%.
- Globally verified completed remains 4/6 packages / 75% while PR #18 is open.
- WP-05's prospective `COMPLETE` and WP-06's prospective `READY` become globally authoritative only after this exact final-state commit passes fresh exact-head gates, is normally merged, and WP-05 post-merge/release verification succeeds.

## WP-05 final package evidence checkpoint
- Canonical branch: `agent/wp-05-live-acceptance-release`.
- Canonical PR: #18, `WP-05: complete live acceptance and release LoreItems`.
- Exact implementation/evidence head recorded by the prospective-completion commit: `8f221932e0ae3a77b51b6c8dc8bdb3276af0b68f`.
- Live `main` before prospective-completion commit: `70a636a25d12d755342d90d6846b86a0e56e865b`.
- Exact-head CI: `31557579319`, success.
- Exact plugin artifact: `9126565698`, `enthusialoreitems-plugin`, `build/libs/EnthusiaLoreItems.jar`.
- Exact verification artifact: `9126565348`, `wp04-verification-8f221932e0ae3a77b51b6c8dc8bdb3276af0b68f`.
- External Codacy `93993107691`: success, zero annotations.
- All 22 dedicated WP-05 acceptance workflows: success.
- PR #18: no requested-changes review; zero unresolved inline review threads on the checkpointed head.
- Independent review-only PR #25: all six actionable review threads resolved/dispositioned.
- Explicit production Sentinel startup: source comment `5261577068`, response `5261578620`, check `93994247049`, job `135`, terminal `PAPER_SMOKE_OK`.
- Sequential explicit production Sentinel restart: source comment `5261626410`, response `5261628944`, job `136`, terminal `PAPER_RESTART_OK`.
- The accepted Sentinel commands bind to exact SHA `8f221932...`, exact CI run `31557579319`, artifact `9126565698`, name `enthusialoreitems-plugin`, and JAR path `build/libs/EnthusiaLoreItems.jar`.
- Automatic reviewable/startup check `93993075097` failed before artifact publication with `ARTIFACT_ACQUISITION_FAILED`; this timing result is historical and not counted as a PASS. The explicit commands were issued only after exact-head CI/artifact success and then passed.

## Remaining boundary before global completion
The prospective state commit itself creates a successor exact head, invalidating predecessor exact-head checks for merge purposes. The same package remains the only active work until all finalization succeeds:

1. Fresh final-head CI, all applicable acceptance workflows, Codacy, artifact/release binding, review state and zero unresolved review threads.
2. Fresh explicit production Sentinel startup then restart on that final head after its exact CI plugin artifact exists, because documentation-only commits also invalidate Sentinel exact-head evidence.
3. Current-main reconciliation immediately before merge; if `main` moved, integrate it non-destructively and repeat final-head gates.
4. Normal merge-commit of PR #18 only.
5. Post-merge `main` CI and automatic production `v1.0.0` tag/release verification against the exact merge commit, including all required assets/checksums.
6. Durable global completion record; then stop without beginning WP-06.

## Blocker
None.

## Exact next action
Fast-forward the canonical branch with the prospective-completion state commit based on exact predecessor `8f221932...`, immediately re-fetch branch/PR for concurrency safety, and verify the newly triggered final-head gates. Do not begin WP-06.
