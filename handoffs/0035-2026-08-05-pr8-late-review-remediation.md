# PR #8 late review remediation

- Date: 2026-08-05
- Phase: Implementation PR 4 — Editing and destructive administration
- Exact subphase: PR 4c1 bounded naturally accessible inventory-backed `TEMPLATE_UPDATE` execution
- Repository: `wsg138/EnthusiaLoreItems`
- Pull request: #8 — `Phase 4c1: execute naturally accessible template updates`
- Branch: `agent/loreitems-pr4c1-accessible-template-updates`
- Starting `main`: `5938c8c3ad14c3bd6b890ac6b998e4bab9c655bc`
- Prior documentation head: `3e7adec6fae288795c83a41fe7e3652c8f16b5ee`
- Final reviewed code head: `bef109b14d511ac9ed34811bfc5e95e9b016ec42`
- Status: late automated-review findings, additional harsh-review coverage, focused regressions, exact-head GitHub Actions, and exact-head Codacy are complete; the documentation-only commit containing this report still requires the permanent exact-head gate before merge

## Why this report exists

Report 0034 was accurate when written, but a fresh CodeRabbit review arrived after the recorded finalization and before merge. The new review contained two legitimate in-scope findings. The branch was not merged. Both findings were investigated and fixed, and the affected recovery path received an additional repository-agent harsh review.

## Late review findings and fixes

### 1. Abandoned natural-inventory scans could permanently block dispatch

A bounded inventory scan that exceeded its continuation limit called `markIncomplete(reference)` but did not retain the reference in either the normal scan backlog or the retry backlog. Because the access registry refuses to drain while any reference remains incomplete, one abandoned scan could block unrelated template-update candidates until another external inventory event happened to enqueue or forget that exact reference.

Commit `e626b2bc087397f98a7e915c70e5e509e689ce0b` reset the abandoned scanner cursor, retained the incomplete reference for bounded retry, and added a controlled end-to-end regression proving that the controller retries the reference on a later drain without another access event.

### 2. Coordinator tests conflated active and expired claims

The coordinator test clock was fixed exactly at `claimExpiresAt`, while the runtime treats `now >= claimExpiresAt` as expired. The purported in-flight completion test therefore did not prove that a valid claim reached physical mutation and durable completion.

Commit `e626b2bc087397f98a7e915c70e5e509e689ce0b` now uses an explicitly expired timestamp for the expiry case and a timestamp before expiry for the valid-claim case. The active-claim regression advances the scheduler and verifies the stored item name, target hidden identity, completion call count, and absence of release or review calls.

## Additional harsh-review finding

Reviewing the incomplete-reference state machine after the CodeRabbit fix found the same liveness defect on scanner exceptions. The exception path also marked a reference incomplete without retaining it for retry.

Commit `d5e48c594e2f3a85930a1e0ef89b6702424b0b1f` consolidates exception and abandonment handling through one `retryIncomplete` path. Both cases now:

1. reset partial scanner cursor state;
2. invalidate and mark the access-registry snapshot incomplete;
3. retain the reference in the bounded retry tier;
4. fall back to the normal bounded scan backlog if the retry tier is saturated.

The fallback is safe because the controller has just consumed a normal scan slot before attempting to retain the failed reference. It avoids silently losing the only reference capable of clearing the incomplete fence while preserving bounded queues and backpressure.

Focused regressions now cover both an abandoned scan and a thrown scan failure, proving that each is reset and retried on the next controller drain without another natural-access event.

## Analyzer remediation

The first consolidated late-review head passed compilation and all Gradle tests, but exact-head Codacy reported two test-style findings: an unnecessary `ServerMock` field and a literal first-attempt comparison. Commit `bef109b14d511ac9ed34811bfc5e95e9b016ec42` removes the field and names the scan-count constants. No production behavior changed in this commit.

## Verification evidence

Permanent GitHub Actions run #726 evaluated the synthetic merge of code head `bef109b14d511ac9ed34811bfc5e95e9b016ec42` into base `5938c8c3ad14c3bd6b890ac6b998e4bab9c655bc` and completed successfully.

- Run ID: `31031491855`
- Job ID: `92393035074`
- Synthetic merge: `6ea83c74fbe7742465ad057d4b6edf6bc0a25542`
- `gradle --no-daemon clean check`: `BUILD SUCCESSFUL in 1m 11s`; 40 actionable tasks, 28 executed, 4 from cache, and 8 up-to-date
- Repository tooling: 3 Python tests passed
- New-code complexity: `No new Codacy-Lizard threshold violations.`
- Exact-head Codacy: `Codacy Static Code Analysis passed on exact head bef109b14d511ac9ed34811bfc5e95e9b016ec42.`
- Overall workflow conclusion: success

The log retains existing low-ranked SpotBugs observations and GitHub Actions Node runtime deprecation warnings, but all configured permanent gates passed. No live Paper/Leaf server, deployment, production database, or production system was accessed or tested.

## Review status

- Both late CodeRabbit threads were marked addressed and resolved after the fixes.
- The earlier CodeRabbit thread remains resolved.
- No requested-changes review was present at the reviewed code head.
- The repository-agent harsh review found and fixed the scanner-exception liveness defect in addition to the reported abandonment path.
- No further confirmed in-scope defect or merge blocker remained at code head `bef109b14d511ac9ed34811bfc5e95e9b016ec42`.

## Why the PR remains independently safe and mergeable

PR 4c1 remains one bounded vertical slice for naturally accessible inventory-backed template updates. The late remediation strengthens liveness without weakening any identity, revision, physical-verification, durable-claim, thread-ownership, or queue bound. Failed or abandoned scans remain fail-closed while retaining a bounded route to eventual retry. Database work remains asynchronous, Bukkit access remains on the owning thread, unloaded chunks remain untouched, and physical completion is recorded only after exact re-read verification.

## Remaining Phase 4 work

PR 4c2 remains responsible for bounded natural-encounter template updates on already-loaded dropped item entities, item frames, glow item frames, and item display entities. Later Phase 4 work retains the remaining staff-facing editing and destructive administration surfaces. None of that later functionality was added here.

## Preserved boundaries

- Only naturally accessible inventory-backed items are updated in PR 4c1, including nested shulker boxes and bundles.
- No global synchronous inventory or world scan exists.
- No unloaded chunk is force-loaded.
- Dropped items, item frames, glow item frames, and item displays remain PR 4c2.
- Scan, retry, continuation, in-flight, and database queues remain bounded.
- Database work remains asynchronous; Paper inventory access and physical mutation remain on the owning thread.
- Hidden instance UUIDs remain stable.
- Mutable nested contents come from the encountered item rather than the definition template.
- Ambiguous identity, revision, location, claim, physical verification, or durable completion remains fail-closed in `REVIEW_REQUIRED` or safely releases an unapplied claim.
- No released migration was edited.
- No deployment, production access, live-server action, staff command/GUI expansion, campaign execution, public API expansion, or EnthusiaTags integration occurred.

## Exact next action

1. Run the permanent GitHub Actions workflow and exact-head Codacy on the documentation-only commit containing this report, `handoffs/CURRENT.md`, and `handoffs/INDEX.md`.
2. Update PR #8 with the final documentation head and exact verification evidence.
3. Reconcile live `main`, PR state, mergeability, open/draft PRs, active branches, reviews, unresolved threads, requested changes, and new comments.
4. If every final gate remains clean, merge PR #8 with a normal merge commit under the user's authorization.
5. Verify the resulting `main` SHA and delete the merged feature branch when supported.
6. Stop without beginning PR 4c2 in this chat.
