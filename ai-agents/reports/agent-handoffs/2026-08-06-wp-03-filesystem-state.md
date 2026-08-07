# WP-03 filesystem and recipient-state checkpoint

- Package: WP-03 — one-use mass distributions
- Status: `IN_PROGRESS`
- Branch: `agent/wp-03-mass-distributions`
- Draft PR: #14
- Starting live `main`: `d77ec61032e5583783694ae349f785495cbf8f31`
- Exact implementation head checkpointed: `bfe248c70c1cdbee4f88b62eb073445e745b8785`

## Completed criteria in this checkpoint

- Exact seven-state recipient model implemented: `UNRESOLVED`, `QUEUED_OFFLINE`, `QUEUED_INVENTORY_FULL`, `RESERVED_IN_FLIGHT`, `REVIEW_REQUIRED`, `DELIVERED`, `CANCELLED`.
- `CampaignRecipientCounts.total()` is the sum of all seven mutually exclusive states; `remaining()` is the first five nonterminal/review states.
- Upgrade migration V6 maps the foundation recipient state names onto the WP-03 contract without discarding persisted recipients and recreates the required indexes/triggers.
- Group directory initialization is bounded to `groups/`, `groups/completed/`, and `groups/cancelled/`.
- Safe immediate-directory `.yml` discovery rejects symlinks, nonregular/unreadable files, traversal/escape attempts, unsupported schema keys, malformed YAML, blank display names, empty/non-string recipient lists, malformed UUID-shaped recipients, and normalized duplicates with diagnostics.
- Java names, leading-`*` Floodgate-style names, and canonical UUID recipients are preserved in the immutable parsed source snapshot.
- Source identity uses a deterministic SHA-256 fingerprint over normalized source path identity plus exact bytes.
- Filesystem marker primitives support verified move-to-active, active-marker repair, completed/cancelled terminal moves, and ensure active markers are not rediscovered as new sources.

## Tests and verification

- CI run #863 on exact head `bfe248c70c1cdbee4f88b62eb073445e745b8785`: SUCCESS.
- Gradle verification: SUCCESS.
- Repository tooling verification: SUCCESS.
- New-code complexity verification: SUCCESS.
- Exact-head Codacy gate: SUCCESS.
- Initial Codacy findings were resolved in-package: Java/test maintainability findings were refactored; V6 was added only to the repository's existing exact SQLite-migration analyzer exclusion list used for V1–V5.

## Findings fixed

- Foundation recipient states did not match the WP-03 contract; migrated and compatibility aliases retained without warning-as-error regressions.
- Campaign cancellation still targeted the old pending state names; corrected.
- Migration tests assumed five schema versions; corrected to six.
- Malformed UUID-like input could fall through as a name; corrected.
- Group parser complexity and literal/locale Codacy findings were removed.

## Remaining acceptance criteria

- Pin the selected definition revision in the durable campaign snapshot.
- Implement one-transaction campaign start including campaign identity, source fingerprint, actor/audit event, immutable recipient snapshot, and activation.
- Ensure replay of the same source identity cannot create a second campaign.
- Add cached/known-name resolution without network correctness dependency and atomic late join binding, including Floodgate-prefixed names.
- Integrate recipients with the hardened direct-delivery queue using pinned revision, durable idempotency, exactly-once physical delivery, offline/full-inventory persistence, bounded retry/backpressure, and review-required crash handling.
- Implement campaign status/pagination, pause/resume/cancel, terminalization, marker reconciliation, startup resume, reload/degraded/shutdown behavior, inventory/join wakeups, metrics, permissions/messages/audit, docs, and WP-02 queue/review integration.
- Add all remaining parser/domain/SQLite/Paper/end-to-end/regression tests.
- Run full-package harsh review and fix every confirmed finding.
- Re-establish exact-head Actions/Codacy after the final implementation commit, resolve all requested changes/unresolved review threads, normally merge, verify live `main`, then mark WP-03 `COMPLETE` and only WP-04 `READY`.

## Blocker

None. A local checkout is unavailable in this environment because unauthenticated container DNS cannot resolve GitHub, so the authenticated GitHub connector remains the durable implementation surface.

## Exact next action

Add pinned definition revision plus an application-level campaign-start command/port and SQLite implementation that creates and activates the complete immutable campaign snapshot and audit record in one transaction before any filesystem marker move or item delivery.
