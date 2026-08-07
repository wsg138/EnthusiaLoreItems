# WP-02 final author-side harsh review and verification — 2026-08-06

## Reviewed state

- Package: WP-02 — destructive administration
- Pull request: #13
- Canonical branch: `agent/wp-02-destructive-administration`
- Starting live `main`: `50ac248b1583739c57b7dcb25b4e949436b736ce`
- Fully reviewed implementation candidate before review remediation: `3cef33dc245061e4d023f4ce55b879e4bb7385e6`
- CodeRabbit Autofix head: `cea33307f57ba2c8ff775e2c2376ed1490c495cb`
- Author remediation heads: `8ade7870bc0256eb4d35bbcd56657f9c7ff2f64c` and `47b4ba39ed335d4599cafac2c1d379feaa0104ef`
- Verified final implementation head: `98d58bd76c69939159a351bd3407c108a3015227`

## Runner recovery and exact-head verification

GitHub Actions was not waived. After GitHub reported recovery, a runner executed the formerly blocked final workflow. That first recovered execution exposed a real regression rather than an outage failure: MockBukkit represents a cleared `ItemDisplay` stack as AIR, and the removal path attempted to serialize it for fingerprinting.

Commit `98d58bd76c69939159a351bd3407c108a3015227` corrected the behavior by treating AIR as absent in resolve, removal, and stored-stack reads while removing redundant null guards. The existing focused regression test then exercised the repaired path.

Run `31137614006` passed on exact implementation head `98d58bd76c69939159a351bd3407c108a3015227` and completed:

- hosted-runner allocation and checkout;
- Java 21 setup;
- Gradle 8.14.3 setup;
- `gradle --no-daemon clean check`;
- repository-tool dependency installation and tests;
- new-code complexity verification;
- exact-head Codacy verification.

External Codacy check `92740655340` succeeded on that exact implementation head with zero annotations.

## Submitted review findings and remediation

The original CodeRabbit COMMENT review posted seven actionable threads. The six implementation defects were validated and repaired:

1. **Page arithmetic overflow** — oversized page numbers enter the user-facing invalid-argument path.
2. **Shutdown permit leakage** — active-actor and semaphore state are released if callback scheduling is rejected.
3. **Empty item-display stacks** — resolution, removal, and stored-stack reads treat AIR as absent and never fingerprint it.
4. **Late-target uniqueness** — reopening late targets cannot collide with another non-terminal row for the same instance.
5. **Audit JSON validity** — evidence and fingerprints escape quotes, reverse solidus, standard escapes, and every control character below U+0020.
6. **Aggregate count integrity** — completed and aborted totals are validated with overflow-safe arithmetic.

The seventh thread concerned coordination publication and was resolved with the earlier state update. The author review also preserved `NONE_OBSERVED` for evidence-gated requeue and abort resolutions.

The incremental CodeRabbit review on `98d58bd76c69939159a351bd3407c108a3015227` completed successfully and posted two coordination findings:

- publish one aligned queue/workspace/handoff snapshot using the recovered final evidence;
- restore the complete conflict-resolution authority order.

This records-only publication sequence addresses both. No submitted review is in `CHANGES_REQUESTED` state.

## Harsh-review risk trace

### Irreversible effects and wrong-target deletion

Destructive intent, operation identity, actor, expected target state, and fixed target snapshots are persisted before physical mutation. Exact removal requires definition, instance UUID, revision, lifecycle, scope/location identity, and fingerprint agreement. Divergence or ambiguous outcome enters durable review instead of deleting a substitute item.

### Idempotency and callback replay

Operation-specific idempotency keys and durable records prevent repeated confirmation, callback replay, restart, or retry from creating a second logical destructive operation or selecting a different target snapshot.

### Purge, full delete, and late copies

Purge retains the active definition while known and naturally returning copies are removed. Full delete excludes the definition from ordinary interfaces after durable acceptance, retains the minimal marker and history, and schedules late copies without force loading. Reopened late targets retain the non-terminal uniqueness fence.

### Ambiguous and malformed evidence

Changed, duplicate, malformed, missing, or unclassifiable outcomes remain preserved. Review resolutions are evidence-gated; there is no blind force-complete, blind retry, or location-only deletion path. Audit details remain valid JSON for permitted evidence strings.

### Threading, bounds, reload, and recovery

Paper item/entity access stays on the server thread and persistence stays off-thread. Claims, scans, retries, confirmations, operation views, and target views are bounded. No chunk is force-loaded. Claims have lease recovery; ambiguous apply/verify boundaries remain reviewable; reload and shutdown clear unsafe transient state without erasing paused operations, markers, history, or unresolved evidence.

### Migration and compatibility

The V5 migration remains forward-only and released migrations were not edited. Java 21, Paper/Leaf 1.21.11, SQLite, Geyser, and Floodgate assumptions remain unchanged. No live Paper/Leaf behavior is claimed beyond automated adapter coverage.

## Publication gate

At this records update:

- WP-02 implementation head `98d58bd76c69939159a351bd3407c108a3015227` has successful exact-head Actions and Codacy evidence;
- CodeRabbit completed its incremental review with no requested-changes state;
- two coordination findings are being addressed consistently across the queue, workspace state, this report, and `latest.md`;
- the final records-only branch head still requires exact-head Actions/Codacy and thread-resolution verification before merge.

## Verdict and exact next action

WP-02 is implementation-complete and prospectively `COMPLETE`, but is not authoritative until normal merge and live-main verification. Finish the aligned records publication, pass the final records-only exact-head gates, resolve the two addressed coordination threads, update the PR body, merge with the normal merge-commit method, verify live `main` and post-merge checks, and stop without claiming or beginning WP-03.