# Handoff 0036 — Natural entity template updates

## Session metadata
- Date/time: 2026-08-05
- Phase: Implementation PR 4 — Editing and destructive administration
- Repository: `wsg138/EnthusiaLoreItems`
- Branch: `agent/loreitems-pr4c2-natural-entity-template-updates`
- Pull request: #9 — `Phase 4c2: update naturally encountered item entities`
- Starting `main`: `7dc27b0599eabc11fd4d7a064f8a393e5411be3e`
- Reported implementation head: `2751c888c44b6b1f7c21833e2eb056423500ab47`
- Session status: ready for review; final documentation-head verification remains before merge

## Objective
Extend durable `TEMPLATE_UPDATE` execution from naturally accessible inventories to already-loaded dropped items, item frames, glow item frames, and item displays without force-loading chunks or adding an unbounded scan.

## Work completed
- Added reload-safe entity references with read, replace, verify, and restore behavior.
- Generalized the existing operator/reference boundary so inventory and entity items share identity, revision, fingerprint, claim, durable completion, retry, and fail-closed review rules.
- Added a persistent loaded-world/chunk/entity cursor with a strict per-tick budget using only loaded chunks.
- Added event fast paths for item and item-display spawn, item-frame placement/change, entity removal, and chunk-topology changes.
- Added one inventory-plus-entity uniqueness fence and shared coordinator/in-flight limit.
- Added `ITEM_DISPLAY` reconciliation evidence.

## CI remediation
Initial head `ea70a610b1a326764f0b9997e583bf8f7b1fe935` failed two MockBukkit sweep tests because the fixtures did not materialize their chunks in MockBukkit's loaded-chunk collection. The production walker correctly sees only loaded chunks. Commit `2751c888c44b6b1f7c21833e2eb056423500ab47` loads the test chunk before dropping the fixture item; this does not add production chunk loading. The same remediation removed redundant null branches reported by SpotBugs for non-null Bukkit item-stack accessors.

## Focused regression coverage
Coverage includes dropped-item, glow-frame, and item-display mutation; entity discovery; bounded sweep publication; event refresh; fail-closed topology replacement; cross-surface duplicate suppression; bounded rejection retry; and item-display reconciliation evidence.

## Harsh review
A separate full-PR review examined lifecycle ownership, scheduler cleanup, traversal bounds, uniqueness fencing, coordinator saturation, entity removal, physical re-resolution, restore behavior, and the inherited durable mutation state machine. It confirmed the CI defects above and found no additional in-scope merge blocker after remediation. Incomplete entity coverage blocks dispatch, topology changes require a replacement sweep, invalid/unloaded entities fail closed, and no unloaded chunk is accessed.

## Verification actually performed
Permanent GitHub Actions run #732 evaluated head `2751c888c44b6b1f7c21833e2eb056423500ab47` successfully.
- Run ID: `31049063928`
- Job ID: `92451703095`
- Gradle clean check: passed
- Repository tooling: passed
- New-code complexity: passed
- Exact-head Codacy: passed

No live Paper/Leaf server, deployment, production database, or production system was accessed.

## Live automation observed
- PR #9 was open, draft, and mergeable.
- No submitted review, requested-changes review, or unresolved thread was present.
- CodeRabbit had not submitted a review or thread.
- `main` remained `7dc27b0599eabc11fd4d7a064f8a393e5411be3e`.

## Important decisions and invariants
- No chunk force-loading or global synchronous world scan.
- Loaded traversal is persistent and bounded.
- Inventory and entity candidates share one uniqueness fence and coordinator budget.
- Hidden instance UUIDs remain stable.
- Completion occurs only after physical re-read verification.
- Ambiguity remains fail-closed.
- No released migration changed.

## Files or modules changed
Primary changes are in the Paper adapter entity controller, events, lifecycle listener, reference/scanner, shared access registry, recovery-worker wiring, operator abstraction, item-display reconciliation, corresponding tests, and `LocationDescriptor.Type.ITEM_DISPLAY`.

## Persistence, state-machine, or API changes
No schema or released migration changed. The existing pending-mutation, claim-lease, retry, audit, recovery, and review-required state machine is reused. The Paper physical reference boundary was generalized internally; no public API was added.

## Unresolved risks or missing evidence
- The documentation-only head containing this report, `CURRENT.md`, and `INDEX.md` still requires permanent exact-head verification.
- No live Paper/Leaf server behavior was tested.
- Any later automated review must be inspected before merge.

## Exact next step
Verify the documentation head, update the PR body, re-check reviews and mergeability, then mark PR #9 ready and merge with a normal merge commit if every gate remains clean. Verify `main`, delete the branch when supported, and stop.

## Required prior reports
- [`0035-2026-08-05-pr8-late-review-remediation.md`](0035-2026-08-05-pr8-late-review-remediation.md)
- [`0034-2026-08-05-pr8-exact-head-finalization.md`](0034-2026-08-05-pr8-exact-head-finalization.md)
- [`0032-2026-08-04-pr8-accessible-template-updates.md`](0032-2026-08-04-pr8-accessible-template-updates.md)
- [`0031-2026-08-04-pr7-mutation-queue-controls.md`](0031-2026-08-04-pr7-mutation-queue-controls.md)
