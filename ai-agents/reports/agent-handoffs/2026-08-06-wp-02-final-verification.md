# WP-02 final author-side harsh review and verification — 2026-08-06

## Reviewed state

- Package: WP-02 — destructive administration
- Pull request: #13
- Canonical branch: `agent/wp-02-destructive-administration`
- Exact reviewed pre-final head: `eddded7254e78113dc29f0421dfc2becbf9194ee`
- Fully reviewed implementation candidate: `3cef33dc245061e4d023f4ce55b879e4bb7385e6`
- Relationship: the exact pre-final head is two commits ahead of the implementation candidate, and those commits modify only queue, workspace, blocker, harsh-review, and latest-handoff documentation. No production or test code changed after the full-package harsh review.

## Verification evidence

GitHub Actions run `31117469546`, attempt 2, completed successfully for exact head `eddded7254e78113dc29f0421dfc2becbf9194ee` after runner recovery.

The run completed every repository workflow step successfully:

1. checkout;
2. Java 21 setup;
3. Gradle 8.14.3 setup;
4. `gradle --no-daemon clean check`;
5. installation of repository-tool dependencies from `tools/requirements.txt`;
6. `python3 -m unittest discover -s tools -p 'test_*.py'`;
7. new-code complexity verification;
8. exact-head Codacy verification.

External Codacy check `92670804638` also completed successfully for the same head with zero annotations.

GitHub Actions was not waived. Earlier setup-only failures remain historical outage evidence, but the recovered exact-head run passed.

## Harsh-review risk trace

### Irreversible effects and wrong-target deletion

Destructive intent, operation identity, actor, expected target state, and target snapshots are persisted before physical mutation. Exact-instance removal requires definition ID, instance UUID, revision, lifecycle, scope/location identity, and fingerprint agreement. Divergence or ambiguous physical outcome enters durable review instead of deleting a substitute item.

### Idempotency and callback replay

Operation-specific idempotency keys and durable operation records prevent duplicate confirmation, callback replay, restart, or retry from creating a second logical destructive operation or selecting a different target snapshot.

### Purge, full delete, and late copies

Purge retains the active definition while durable targets remove known and naturally returning copies. Full delete excludes the definition from ordinary interfaces after durable acceptance, retains the minimal deletion marker and history, and schedules late-returning copies without force loading. The late-copy path preserves an intentionally `PAUSED` parent state.

### Ambiguous and malformed evidence

Changed, duplicate, malformed, missing, or unclassifiable outcomes are preserved as evidence. The supported review resolutions remain evidence-gated; there is no blind force-complete, blind retry, or location-only deletion path.

### Main-thread and bounded-work safety

Paper item and entity access remains on the server thread. Persistence remains off-thread. Claims, scanning, retries, confirmation sessions, operation views, and target views are bounded and paginated. No chunk is force-loaded and no global synchronous scan is introduced.

### Reload, shutdown, crash, and lease recovery

Durable intent precedes mutation, claims have lease recovery, ambiguous apply/verify boundaries remain reviewable, and reload/shutdown cleanup prevents stale sessions or unsafe continuation. Restart does not erase paused state, late-copy markers, operation history, or unresolved evidence.

### History and ordinary-interface visibility

Privileged history remains available for audit and recovery. Deleted definitions are excluded from normal GUI search, give/adopt/editor selection, and tab completion while the minimal marker remains available for late-copy enforcement.

### Migration and compatibility

The V5 migration is forward-only and previously released migrations are unchanged. Java 21, Paper/Leaf 1.21.11, SQLite, Geyser, and Floodgate compatibility assumptions remain unchanged from the reviewed implementation.

## Review reconciliation

At preparation of this report:

- PR #13 was ready for review;
- submitted reviews: none;
- requested changes: none;
- unresolved review threads: zero;
- mergeability: mergeable.

## Findings

No remaining release blocker or same-package defect was found in the exact reviewed state.

The coordination commit containing this report changes documentation and prospective workflow state only. That new exact head must still pass final GitHub Actions and Codacy and must be rechecked for new reviews or threads before merge.

## Verdict

WP-02 is ready for its prospective COMPLETE coordination commit and final exact-head publication gates. After those gates pass, PR #13 may be merged only with the normal merge-commit method, followed by live-`main` and post-merge verification. The completing worker must stop without claiming or beginning WP-03.