# PR #8 exact-head finalization and crash-recovery remediation

- Date: 2026-08-05
- Phase: Implementation PR 4 — Editing and destructive administration
- Exact subphase: PR 4c1 bounded naturally accessible inventory-backed `TEMPLATE_UPDATE` execution
- Repository: `wsg138/EnthusiaLoreItems`
- Pull request: #8 — `Phase 4c1: execute naturally accessible template updates`
- Branch: `agent/loreitems-pr4c1-accessible-template-updates`
- Starting `main`: `5938c8c3ad14c3bd6b890ac6b998e4bab9c655bc`
- Resumed branch head: `47f1dbed38119ea69b8c1459cbeb4971cdbf54d4`
- Final reviewed code head: `6704415b77302744f8b9dc740b287416c94a15e4`
- Status: code, focused regressions, harsh review, exact-head GitHub Actions, and exact-head Codacy are complete; this documentation-only handoff commit still requires the permanent exact-head gate before merge

## Live-state reconciliation

Live GitHub was inspected before changing the branch. `main` remained at `5938c8c3ad14c3bd6b890ac6b998e4bab9c655bc`, PR #8 remained the only open pull request, and its recorded branch was still the relevant unfinished work. The PR was non-draft and mergeable. No requested-changes review existed. The single actionable CodeRabbit inline thread was already resolved, and no unresolved review thread remained. Active remote branches were inspected; no newer merge made the handoff stale.

A local checkout was not available: authenticated `gh` was absent and direct clone could not resolve GitHub from the execution environment. Repository changes therefore used the GitHub connector's normal Git object and fast-forward reference operations. No force push, rebase, squash, direct `main` push, temporary workflow, deployment, production access, or live-server action occurred.

## Work completed in this finalization item

### Exact-head Codacy remediation

Permanent CI run #719 on documentation head `47f1dbed38119ea69b8c1459cbeb4971cdbf54d4` exposed four exact-head Codacy findings that were not present in the previous implementation-head evidence:

1. a false-positive concurrent-map recommendation for a method-local aggregation map;
2. two member/accessor naming collisions;
3. a nullable test sentinel used to control an in-flight preparation.

Commit `011687a1d6320cf8aff5438a1578d7bab8458028` addressed the local-map warning with a narrow method suppression and explanatory comment, renamed conflicting members, and replaced the nullable test sentinel with a queue. Permanent run #720 then showed one remaining naming collision. Commit `92735e7129bed7d0a37a4d1657d7cb1351a40ef5` removed that final collision without changing execution behavior.

### Harsh-review crash-window defect

A separate harsh review of the complete PR found a real durability defect in the target-revision recovery path. Preparation intentionally permits an encountered physical item already carrying the desired template revision while SQLite still records the older applied revision. The previous completion fence then rejected its own valid claim because the stored applied revision matched neither the observed physical revision nor the target, which are equal in this crash state. The existing restart test manually advanced SQLite first and therefore did not exercise the actual failure window.

Commit `6704415b77302744f8b9dc740b287416c94a15e4` fixes that state by accepting an older stored applied revision only when the physically observed revision already equals the target revision. All other fences remain mandatory: definition identity, instance identity, mutation target, instance desired revision, active lifecycle, current claim token, and unexpired lease. The same transaction still advances the instance revision, mutation states, and audit record atomically.

The regression `completesAfterRestartWhenThePhysicalItemAlreadyHasTheTargetRevision` now prepares from a physical target-revision identity while SQLite remains on the previous applied revision, proves the database is still behind before completion, then verifies the instance advances and the mutation reaches `COMPLETED`.

## Independent harsh review result

The review covered the full PR rather than only the final commits, including plugin lifecycle and wiring, bounded recovery worker behavior, naturally accessible inventory discovery, duplicate fencing, reload-safe references, nested shulker and bundle traversal, physical mutation verification, asynchronous application coordination, SQLite preparation/completion/audit transactions, tests, build configuration, plugin metadata, and handoff documentation.

One confirmed merge blocker was found and fixed: the target-revision physical/older-database crash state described above. After the fix, no further confirmed in-scope defect or merge blocker remained. Nonblocking observations about constructor coupling, shared configuration limits, and generic shutdown wording did not justify scope expansion.

## Verification evidence

Permanent GitHub Actions run #722 evaluated the synthetic merge of code head `6704415b77302744f8b9dc740b287416c94a15e4` into base `5938c8c3ad14c3bd6b890ac6b998e4bab9c655bc` and completed successfully.

Verified evidence from job `verify`:

- `gradle --no-daemon clean check`: `BUILD SUCCESSFUL` in 1 minute 8 seconds; 40 actionable tasks, with 28 executed, 4 from cache, and 8 up-to-date;
- repository tooling: 3 Python tests ran and passed;
- new-code complexity: `No new Codacy-Lizard threshold violations.`;
- exact-head Codacy: `Codacy Static Code Analysis passed on exact head 6704415b77302744f8b9dc740b287416c94a15e4.`

The log contains existing low-ranked SpotBugs observations and Node runtime deprecation warnings, but the configured Gradle and permanent workflow gates completed successfully. No live Paper/Leaf server behavior was tested.

## Review status

- PR #8 remained non-draft and mergeable at the reviewed code head.
- No requested-changes review existed.
- The only substantive CodeRabbit inline thread was resolved.
- No unresolved review thread remained.
- A later CodeRabbit retry had been rate-limited rather than producing a new actionable review; it was not repeatedly retriggered while the branch changed.
- The separate repository-agent harsh review found and resolved the crash-window defect above.

## Why this PR remains independently safe and mergeable

PR 4c1 delivers one bounded vertical slice: naturally accessible inventory-backed template updates. It uses the durable mutation queue and claim protocol established by prior PRs, keeps database work asynchronous, keeps Bukkit inventory access and mutation on the owning thread, refuses to force-load unloaded chunks, preserves mutable nested contents, and fails closed on ambiguous identity, location, revision, claim, or physical verification. The work does not require PR 4c2 entity-surface discovery to be correct for the inventory-backed surfaces it covers.

## Remaining Phase 4 work

PR 4c2 remains responsible for bounded natural-encounter template updates on already-loaded dropped item entities, item frames, glow item frames, and item display entities. Later Phase 4 work also still includes the remaining staff-facing editing/destructive administration surfaces specified by the implementation plan. None of that later functionality was pulled into PR 4c1.

## Preserved boundaries

- Only naturally accessible inventory-backed items are updated in this subphase, including nested shulker boxes and bundles.
- No global synchronous inventory or world scan exists.
- No unloaded chunk is force-loaded.
- Dropped items, item frames, glow item frames, and item displays remain PR 4c2.
- Database work remains asynchronous and bounded; Bukkit inventory access and physical mutation remain on the owning thread.
- Hidden instance UUIDs remain stable.
- Mutable container contents come from the encountered instance rather than being overwritten from the definition template.
- Ambiguous or conflicting state remains fail-closed in `REVIEW_REQUIRED` or safely releases an unapplied claim.
- No released migration was edited.
- No production database, production server, deployment, staff command/GUI expansion, public API expansion, campaign execution, or EnthusiaTags integration was added.

## Exact next action

1. Run the permanent GitHub Actions and exact-head Codacy gates on the documentation-only commit containing this report, `handoffs/CURRENT.md`, and `handoffs/INDEX.md`.
2. Update the PR body with the exact final head and verification evidence.
3. Reconfirm live `main`, PR mergeability, reviews, unresolved threads, requested changes, and active branches.
4. Merge PR #8 with a normal merge commit under the user's authorization when the final head is clean.
5. Verify the resulting `main` SHA and delete the merged feature branch when supported.
6. Stop without beginning PR 4c2 in this chat.
