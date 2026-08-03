# Handoff 0009 — Distribution campaign and recipient persistence

## Session metadata
- Date/time: 2026-08-02 21:43 EDT
- Phase: Implementation PR 1 — Foundation and durable core
- Repository: `wsg138/EnthusiaLoreItems`
- Branch: `agent/loreitems-pr1-foundation`
- Pull request: #2 — Foundation and durable core
- Reported implementation head: `1e4a9d7a497ce5963a724c23d14cf45bb1975fe9`
- Session status: in progress

## Objective

Implement only the distribution campaign and recipient domain/application persistence family recorded as the exact next step in handoff 0008. Keep group-file I/O, physical campaign delivery, commands, GUIs, and later implementation phases out of scope.

## Work completed

- Added platform-free campaign and recipient lifecycle models.
- Added immutable campaign source identity and recipient snapshot identity models.
- Added application repository ports for campaign lifecycle, cancellation, recipient insertion, unresolved-name binding, bounded pages/counts, claims, delivery completion, review escalation, and expired-claim recovery.
- Added SQLite campaign and recipient repositories using the existing bounded storage runtime and explicit transactions.
- Hardened V1 campaign and recipient schema constraints and indexes.
- Added SQLite migration parsing support for multi-statement trigger bodies so immutable identity and snapshot-sealing triggers execute as one statement.
- Added focused domain and SQLite tests for lifecycle, source-fingerprint uniqueness, immutable snapshots, case-insensitive name binding with Floodgate prefix preservation, bounded paging/counts, claim fencing, cancellation, completion, and restart recovery.
- Kept PR #2 on the existing branch and left it as a draft. No merge was attempted.

## Important decisions and invariants

- Campaign lifecycle is `DRAFT -> ACTIVE <-> PAUSED -> COMPLETED | CANCELLED`; terminal states cannot reopen.
- Activation requires a non-empty contiguous recipient snapshot beginning at index zero.
- Completion requires every recipient in the immutable snapshot to be `DELIVERED`.
- Cancellation is atomic with pending-recipient cancellation. `PENDING_NAME`, `PENDING_OFFLINE`, and `PENDING_SPACE` recipients become `CANCELLED`; already delivered recipients stay delivered; a previously reserved in-flight recipient retains its fenced claim and may record a verified delivery after campaign cancellation.
- Campaign source fingerprints are normalized to lower case and unique.
- Recipient keys are immutable snapshot identities. Unresolved names use a case-insensitive `name:` key while preserving the original value and any Floodgate `*` prefix. Atomic binding writes the authoritative UUID to `player_id` without replacing the snapshot key or original value.
- A campaign cannot contain two recipients bound to the same authoritative player UUID, and one lore instance cannot satisfy multiple recipient deliveries.
- Claims are bounded, token-fenced, lease-fenced, retry-aware, and only created while the campaign is `ACTIVE`.
- Expired ambiguous claims move to `REVIEW_REQUIRED` in bounded batches after restart rather than being retried automatically.
- Group-file parsing/moves and inventory mutation remain outside this persistence slice.

## Files or modules changed

- `domain/src/main/java/net/enthusia/loreitems/domain/DistributionCampaignState.java`
- `domain/src/main/java/net/enthusia/loreitems/domain/CampaignRecipientState.java`
- `domain/src/main/java/net/enthusia/loreitems/domain/CampaignRecipientKey.java`
- `domain/src/main/java/net/enthusia/loreitems/domain/DistributionCampaign.java`
- `domain/src/main/java/net/enthusia/loreitems/domain/CampaignRecipient.java`
- `application/src/main/java/net/enthusia/loreitems/application/CampaignCancellationResult.java`
- `application/src/main/java/net/enthusia/loreitems/application/CampaignRecipientCounts.java`
- `application/src/main/java/net/enthusia/loreitems/application/DistributionCampaignRepository.java`
- `application/src/main/java/net/enthusia/loreitems/application/DistributionRecipientRepository.java`
- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDistributionCampaignRepository.java`
- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDistributionRecipientRepository.java`
- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/MigrationRunner.java`
- `adapters-sqlite/src/main/resources/db/migration/V1__foundation.sql`
- `domain/src/test/java/net/enthusia/loreitems/domain/DistributionCampaignTest.java`
- `adapters-sqlite/src/test/java/net/enthusia/loreitems/sqlite/SQLiteDistributionRepositoryTest.java`

## Persistence, state-machine, or API changes

- `distribution_campaigns` now has checked lifecycle and timestamp consistency, immutable source identity, definition linkage, unique source fingerprint, and a state/update index.
- `distribution_recipients` now stores immutable snapshot index/key/original value, optional authoritative player UUID, checked recipient state metadata, claim lease/token, attempt/retry metadata, delivered instance/time, and update time.
- Partial unique indexes enforce one authoritative player per campaign and one delivered/review-linked instance across recipients.
- Claimable, unresolved-name, and expired-claim indexes support bounded deterministic operations.
- SQLite triggers enforce campaign identity immutability, recipient snapshot immutability, and draft-only snapshot insertion.
- `MigrationRunner` keeps normal statements semicolon-delimited while treating a `CREATE TRIGGER ... BEGIN ... END;` block as one executable statement.

## Verification actually performed

Local focused verification before publishing the fixes:

- Compiled the new domain, application, SQLite adapter, and focused test sources with Java 21 using `javac --release 21 -Xlint:all -Werror`; passed.
- Executed a Java constructor smoke test covering unresolved Floodgate-prefixed names and bound unresolved-name recipients; passed.
- Reflected over the migration splitter and confirmed V1 produced 32 statements including exactly three complete trigger statements; passed.
- Executed V1 and focused lifecycle/cancellation/binding/recovery behavior against SQLite; passed.

GitHub Actions run 119 (`30777192084`), job `91574820110`, checked out merge commit `db60774f8094b6339fa752bd3abbdd5c42cffc8c` containing implementation head `98b2d97ee864452feaa527db044e301d980976e2` and failed `gradle --no-daemon clean check`:

- Compact record-constructor validation accessed unassigned record fields and caused two domain-test null-pointer failures.
- The prior migration runner split trigger bodies at internal semicolons and caused `SQLITE_ERROR: incomplete input`, degrading SQLite startup in dependent tests.
- Both defects were corrected on the same branch; the failed run remains part of the evidence trail.

GitHub Actions run 121 (`30777405515`), job `91575434358`, checked out merge commit `03ca48bb360dc74f3b9d67747ad749a12d911c09` containing implementation head `1e4a9d7a497ce5963a724c23d14cf45bb1975fe9` and ran `gradle --no-daemon clean check`:

- `BUILD SUCCESSFUL in 1m 3s`.
- 31 actionable tasks: 23 executed, 8 up-to-date.
- Domain, application, SQLite adapter, plugin, and architecture test tasks passed.
- Project compilation remained under the repository `-Xlint:all -Werror` policy.

## Live automation observed

At implementation head `1e4a9d7a497ce5963a724c23d14cf45bb1975fe9`:

- PR #2 was open, mergeable, unmerged, and remained a draft.
- GitHub Actions run 121 passed at the exact implementation head.
- CodeRabbit reported success but skipped substantive review because the PR is a draft; its comment refreshed at `2026-08-03T01:40:00Z`.
- Codacy refreshed at `2026-08-03T01:42:37Z` and reported **Not up to standards** with 100 new findings: 30 high and 70 medium. The visible aggregate listed 15 high compatibility findings; 51 medium and 4 high error-prone findings; 9 high security findings; 18 medium complexity findings; and 1 medium plus 2 high performance findings.
- The GitHub connector exposed the Codacy aggregate comment but not the detailed per-file issue list, so individual findings were not validated or dismissed in this session.
- No submitted reviews and no unresolved inline review threads were visible.

## Unresolved risks or missing evidence

- Codacy is red at the exact implementation head. The detailed 100 findings must be inspected before this repository slice is treated as complete or the next persistence family begins.
- No live Paper/Leaf server was started.
- No group file was parsed, moved, quarantined, retried, or recovered.
- No item was physically inserted into a player inventory, and no online/offline/full-inventory behavior was exercised against Bukkit.
- No command, GUI, item creation/adoption, protection listener, tracking/reconciliation execution, editing, or deletion execution was added.
- The migration trigger splitter is intentionally scoped to the repository's line-oriented migration format; future migration formatting must preserve complete normal statements ending in `;` and complete trigger blocks ending on `END;`.

## Exact next step

Do not begin deleted-definition marker persistence yet. First inspect Codacy's detailed issue list for the exact PR head and triage only findings introduced or exposed by the distribution campaign/recipient slice and the migration-runner trigger support. Fix every validated compatibility, error-prone, security, performance, or correctness issue on `agent/loreitems-pr1-foundation`, rerun `gradle --no-daemon clean check`, and verify the refreshed Codacy result, PR comments, and unresolved threads.

After that focused Codacy triage is resolved or each remaining finding is specifically demonstrated to be false/out of scope, the next repository family is deleted-definition marker domain/application persistence plus its SQLite adapter and focused tests. Continue to defer group-file I/O, physical campaign delivery, commands, GUIs, and later phases.

## Required prior reports

- [`0008-2026-08-02-pr2-tracking-persistence.md`](0008-2026-08-02-pr2-tracking-persistence.md) — preceding tracking persistence slice and the distribution-persistence scope resumed here.
- [`0007-2026-08-02-pr2-unit-of-work-verification.md`](0007-2026-08-02-pr2-unit-of-work-verification.md) — verified unit-of-work boundary and prior automation state.
- [`0005-2026-08-02-pr2-definition-instance-persistence.md`](0005-2026-08-02-pr2-definition-instance-persistence.md) — definition/revision/instance identity and lifecycle used by campaign delivery references.
- [`0003-2026-08-02-pr2-storage-runtime.md`](0003-2026-08-02-pr2-storage-runtime.md) — bounded executor, storage lifecycle, connection ownership, and recovery rules.
