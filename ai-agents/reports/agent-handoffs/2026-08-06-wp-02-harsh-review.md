# WP-02 harsh-review verification checkpoint — 2026-08-06

## Durable claim

- Package: WP-02 — destructive administration
- Status: `VERIFYING`
- Branch: `agent/wp-02-destructive-administration`
- Draft PR: #13
- Starting live `main`: `50ac248b1583739c57b7dcb25b4e949436b736ce`
- Resume starting head: `956f8c9a433d2819bbec16f072f7a44149fbbbad`
- Reviewed implementation candidate: `3cef33dc245061e4d023f4ce55b879e4bb7385e6`

## Completed package work

The reviewed candidate implements the complete WP-02 contract: durable exact removal, purge, and full delete; fixed confirmation snapshots; incremental natural-access physical removal; exact identity/revision/location/fingerprint fences; changed and ambiguous evidence preservation; late-copy handling; pause/resume; recovery; operation and target inspection; metrics; evidence-gated review; GUI and command controls; permissions; lifecycle cleanup; and operator documentation.

## Harsh-review findings

### 1. Exact-head Codacy identifier collisions

Two PMD findings remained at the resumed head: a metrics route constant collided with its handler name, and the destructive executor field collided with its accessor. Both identifiers and their call sites were renamed without suppressions.

### 2. Late-copy pause-fence violation

Creating a late full-delete target unconditionally set the parent operation to `ACTIVE`. A deliberately paused delete could therefore resume when a late copy was naturally encountered. The update now preserves `PAUSED`, and a regression test verifies that no claim is issued until an explicit resume.

### 3. Unknown physical outcome was not durable review evidence

Paper mutation exceptions could report `UNKNOWN`, but the SQLite review transition accepts only durable classified outcomes. The application boundary now converts unclassifiable outcomes to `AMBIGUOUS` before persistence, and a regression test verifies immediate review-required evidence.

### 4. Confirmation token did not bind target identity/location

The token included aggregate target, queue, and anomaly counts but not the actual target snapshot. A target could move or be replaced while counts stayed equal. The preview now streams a deterministic SHA-256 digest over each target UUID, applied revision, current state, and location fields and includes that digest in the confirmation token. A regression test moves an exact target after preview and verifies `STALE_CONFIRMATION`.

## Verification evidence

- `da1bff45a82df8f5b0e855720c6eada7e1b5d016`: GitHub Actions `31116258795` succeeded; Codacy `92666693317` succeeded with zero annotations.
- `3cef33dc245061e4d023f4ce55b879e4bb7385e6`: Codacy `92668084949` succeeded with zero annotations.
- GitHub Actions `31116665464` for `3cef33dc245061e4d023f4ce55b879e4bb7385e6` remained in progress at runner setup when this checkpoint was prepared and is not claimed as passing.
- No submitted reviews, requested changes, or unresolved review threads existed at checkpoint preparation.
- Live Paper/Leaf server behavior was not tested or claimed.

## Remaining gates

1. Successful exact-head Actions and Codacy for the checkpoint commit.
2. Ready-for-review transition and complete review/thread reconciliation.
3. Prospective COMPLETE coordination commit, with only WP-03 unlocked as READY, 2 of 6 complete, and 40% weighted progress.
4. Successful exact-head final gates.
5. Normal merge and verification of the merge commit, live `main`, committed state, and post-merge CI.

## Exact next action

Verify the checkpoint commit’s exact-head checks. Repair any same-package failure; otherwise mark PR #13 ready and reconcile review state before preparing the final COMPLETE transition.
