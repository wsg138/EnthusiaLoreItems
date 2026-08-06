# WP-02 final author-side harsh review and verification — 2026-08-06

## Reviewed state

- Package: WP-02 — destructive administration
- Pull request: #13
- Canonical branch: `agent/wp-02-destructive-administration`
- Fully reviewed implementation candidate before publication coordination: `3cef33dc245061e4d023f4ce55b879e4bb7385e6`
- Prospective COMPLETE coordination commit: `201103a417018b01558180bab955838a3bdeb7e8`
- CodeRabbit Autofix commit: `cea33307f57ba2c8ff775e2c2376ed1490c495cb`
- Author remediation commits: `8ade7870bc0256eb4d35bbcd56657f9c7ff2f64c` and `47b4ba39ed335d4599cafac2c1d379feaa0104ef`

## Runner recovery and verification history

GitHub Actions was not waived. Earlier jobs that stopped during `Set up job` remain historical platform-outage evidence, but runner recovery was proven by successful exact-head workflows:

- run `31117469546`, attempt 2, passed on `eddded7254e78113dc29f0421dfc2becbf9194ee`;
- run `31123084632` passed on coordination head `201103a417018b01558180bab955838a3bdeb7e8`;
- run `31123973081` passed on CodeRabbit Autofix head `cea33307f57ba2c8ff775e2c2376ed1490c495cb`.

Each successful workflow executed checkout, Java 21, Gradle 8.14.3, `gradle --no-daemon clean check`, repository-tool dependency installation, all repository-tool tests, new-code complexity verification, and exact-head Codacy verification.

Exact remediation head `47b4ba39ed335d4599cafac2c1d379feaa0104ef` passed external Codacy check `92692113429` with zero annotations. The contents-API handoff commit containing this report must still receive final exact-head Actions and Codacy evidence before merge.

## Submitted review findings and remediation

CodeRabbit submitted a COMMENT review after the coordination head passed. Six actionable implementation threads were validated and repaired on the same WP-02 branch:

1. **Page arithmetic overflow** — oversized page numbers are now converted to a bounded command argument error rather than escaping into Bukkit dispatch.
2. **Shutdown permit leakage** — the active-actor marker and semaphore permit are released even when scheduling the async completion callback is rejected.
3. **Nullable item-display stacks** — entity resolution, removal, and stored-stack reads now safely handle a null `ItemStack`.
4. **Late-target uniqueness** — reopening a late target is fenced against collision with another non-terminal row for the same instance.
5. **Audit JSON validity** — fingerprints and evidence text use shared escaping that covers quotes, reverse solidus, standard escapes, and every remaining control character below U+0020.
6. **Aggregate count integrity** — completed and aborted counts are validated against the target total with overflow-safe subtraction.

The final author review also preserved `NONE_OBSERVED` evidence for evidence-gated requeue and abort resolutions instead of rewriting it to `UNKNOWN`.

Focused regression tests were added for page-offset overflow, nullable item displays, complete JSON control escaping, and overflowing terminal-count sums. Existing full-package tests continue to cover claim uniqueness, review transitions, shutdown behavior, and destructive operation recovery.

## Harsh-review risk trace

### Irreversible effects and wrong-target deletion

Destructive intent, operation identity, actor, expected target state, and target snapshots are persisted before physical mutation. Exact-instance removal requires definition ID, instance UUID, revision, lifecycle, scope/location identity, and fingerprint agreement. Divergence or ambiguous physical outcome enters durable review instead of deleting a substitute item.

### Idempotency and callback replay

Operation-specific idempotency keys and durable operation records prevent duplicate confirmation, callback replay, restart, or retry from creating a second logical destructive operation or selecting a different target snapshot.

### Purge, full delete, and late copies

Purge retains the active definition while durable targets remove known and naturally returning copies. Full delete excludes the definition from ordinary interfaces after durable acceptance, retains the minimal deletion marker and history, and schedules late-returning copies without force loading. The late-copy path preserves an intentionally `PAUSED` parent state and now retains the non-terminal uniqueness fence.

### Ambiguous and malformed evidence

Changed, duplicate, malformed, missing, or unclassifiable outcomes are preserved as evidence. Supported review resolutions remain evidence-gated; there is no blind force-complete, blind retry, or location-only deletion path. Audit details remain valid JSON for every permitted evidence string.

### Main-thread and bounded-work safety

Paper item and entity access remains on the server thread. Persistence remains off-thread. Claims, scanning, retries, confirmation sessions, operation views, and target views are bounded and paginated. No chunk is force-loaded and no global synchronous scan is introduced.

### Reload, shutdown, crash, and lease recovery

Durable intent precedes mutation, claims have lease recovery, ambiguous apply/verify boundaries remain reviewable, and reload/shutdown cleanup prevents stale sessions or unsafe continuation. Restart does not erase paused state, late-copy markers, operation history, or unresolved evidence. Async command permits are released on scheduler rejection.

### History and ordinary-interface visibility

Privileged history remains available for audit and recovery. Deleted definitions are excluded from normal GUI search, give/adopt/editor selection, and tab completion while the minimal marker remains available for late-copy enforcement.

### Migration and compatibility

The V5 migration is forward-only and previously released migrations are unchanged. Java 21, Paper/Leaf 1.21.11, SQLite, Geyser, and Floodgate compatibility assumptions remain unchanged.

## Current review and publication gate

At preparation of this updated report:

- PR #13 is ready for review;
- no submitted review is in `CHANGES_REQUESTED` state;
- the six actionable threads have code fixes but remain to be formally resolved after final exact-head verification;
- external exact-head Codacy is clean on the immediately preceding remediation head;
- the final contents-API commit must pass GitHub Actions and Codacy.

## Verdict and exact next action

WP-02 remains in final verification. Inspect the exact-head workflow created by this handoff update, repair any repository failure, confirm exact-head Codacy has zero annotations, resolve the six addressed review threads, update the PR body with the final evidence, merge only through the normal merge-commit method, verify live `main` and its post-merge workflow, and stop without claiming or beginning WP-03.