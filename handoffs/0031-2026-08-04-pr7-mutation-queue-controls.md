# Handoff 0031 — PR #7 mutation queue controls

Date: 2026-08-04 (America/Chicago)
Repository: `wsg138/EnthusiaLoreItems`
Implementation phase: PR 4 — Editing and destructive administration
Exact logical item: PR 4b — typed durable mutation queue and operator review controls
Pull request: #7 — `PR 4b: add typed mutation queue and review controls`
Branch: `agent/loreitems-pr4-mutation-queue-controls`
Starting `main`: `a375b07d2e2e32b49ba4a4dceab290d1bc2b832f`
Reviewed implementation head: `76c6bf5d66ebf9c57e163e8734b1f91b94c279f5`

## Reconciliation result

`handoffs/CURRENT.md` on `main` referred to PR #6 as open, but live GitHub showed PR #6 already merged into `main` at `a375b07d2e2e32b49ba4a4dceab290d1bc2b832f`. No unfinished relevant pull request existed, so this agent advanced to the next independently reviewable PR 4 subphase rather than reopening merged work.

Before implementation, the agent read `CHATGPT_START_HERE.md`, the stale `handoffs/CURRENT.md`, latest report 0030, required prior report 0029, the relevant requirements, architecture, and implementation-plan sections, and the relevant current source, tests, build configuration, plugin metadata, and documentation. Live repository state took priority over the stale handoff.

## Delivered subphase

PR #7 establishes durable queue control required before any Paper item-replacement worker is allowed to run:

- `PendingMutationRepository` claims and monitoring can be scoped by mutation type, preventing a worker from claiming unrelated future mutation classes.
- `PendingMutationState` now supports explicit `REVIEW_REQUIRED -> PENDING` retry and `REVIEW_REQUIRED -> CANCELLED` resolution.
- `CANCELLED` is terminal and excluded from nonterminal monitoring and rollout blocking.
- `PendingMutationReviewStore` and `SQLitePendingMutationReviewStore` perform compare-and-set review resolution and append the matching audit event in the same SQLite transaction.
- Retry clears stale claim data and makes work immediately claimable without resetting the durable attempt count.
- Cancellation clears claim/retry scheduling data and cannot be resolved or claimed again.
- V3 is a forward-only migration that rebuilds `pending_mutations` with the `CANCELLED` state, preserves existing rows and foreign keys, recreates prior indexes, and adds type-scoped claim/review indexes.
- Template revision rollout blocking now ignores `COMPLETED` and `CANCELLED` work, so an explicitly cancelled ambiguous rollout can be superseded by a corrected later revision.
- Existing Paper recovery fakes were updated for the type-scoped repository contract.
- Immutable V3 SQL is covered by the same narrow Codacy SQL exclusion already used for V1 and V2; Java and test analysis remain enabled.

## Focused regression coverage

Added or strengthened coverage for:

- claim isolation between `TEMPLATE_UPDATE` and another mutation type;
- due-time selection, bounded claims, claim-token fencing, and lease expiry;
- typed nonterminal monitoring;
- preservation of revisions above the integer range;
- bounded expired-claim recovery after restart;
- audit-backed retry and immediate reclaim;
- terminal cancellation and repeated-resolution rejection;
- mutation-type mismatch rejection without state or audit changes;
- transaction rollback when audit insertion fails after the state update;
- V2-to-V3 migration, new indexes, row preservation, and `CANCELLED` acceptance;
- replacement template revision start after explicit cancellation of reviewed work;
- updated Paper recovery test doubles for the repository API.

## Verification evidence

Local checks performed before publishing:

- Java 21 changed production subset compilation with `--release 21 -Xlint:all -Werror` against dependency-compatible stubs: passed.
- Java 21 changed-test syntax compilation against dependency-compatible stubs: passed.
- Direct SQLite V3 migration smoke test with foreign keys enabled, retained `REVIEW_REQUIRED` data, a successful `CANCELLED` transition, the new indexes, and an empty foreign-key check: passed.

Permanent exact-head verification for `76c6bf5d66ebf9c57e163e8734b1f91b94c279f5`:

- GitHub Actions run: `30921848630`.
- Verify job: `92034182891`.
- Tested merge ref: `2279ba3ee891337dcbd3580801cd6c8b137832f9`, merging the exact PR head into starting `main` `a375b07d2e2e32b49ba4a4dceab290d1bc2b832f`.
- `gradle --no-daemon clean check`: `BUILD SUCCESSFUL in 1m 11s`; 40 actionable tasks, 32 executed and 8 up-to-date.
- Repository tooling: 3 tests passed.
- New-code complexity: no new Codacy-Lizard threshold violations.
- Exact-head Codacy: `Codacy Static Code Analysis passed on exact head 76c6bf5d66ebf9c57e163e8734b1f91b94c279f5.`
- Entire workflow conclusion: success.

The repository's configured SpotBugs reporting still prints pre-existing baseline warnings and one hard-coded branch-selected SQL warning in `SQLitePendingMutationRepository.listNonTerminal`; the permanent Gradle gate passed. The query alternatives are compile-time constants and all values remain bound parameters, so the remaining low-confidence warning was reviewed as a false positive rather than a confirmed SQL-injection defect. No analyzer was weakened to suppress Java findings.

## Harsh review and review remediation

A separate hostile review covered migration safety, foreign-key preservation, claim fencing, mutation-type isolation, transaction atomicity, retry/cancellation semantics, revision supersession, and phase boundaries.

Confirmed issues and fixes:

1. The initial tests did not prove that an audit insert failure rolls back the state transition. Added `auditFailureRollsBackTheStateResolution`, which forces an SQLite audit-trigger abort and verifies the mutation remains `REVIEW_REQUIRED`.
2. Exact-head Codacy reported one repeated test event literal. Replaced it with named constants and reran the complete exact-head workflow successfully.
3. CodeRabbit approved the PR but raised one actionable inline finding: review-resolution SQL was selected through a local dynamic string, causing a SpotBugs warning. Split retry and cancellation into separate constant prepared statements and helper methods at `76c6bf5d66ebf9c57e163e8734b1f91b94c279f5`; the thread was resolved and the complete exact-head workflow passed.

CodeRabbit's substantive review completed with approval. The combined CodeRabbit status on the reviewed fix head was success. No requested-changes review or unresolved review thread remained before documentation finalization.

## Why this subphase is independently safe

This PR does not activate physical item mutation. It only supplies durable, type-fenced queue and operator-resolution primitives required by the later executor. Unknown or ambiguous work remains fail-closed in `REVIEW_REQUIRED`; cancellation requires an explicit audited operator action; claims remain bounded and lease-fenced; no global inventory scan or chunk force-load is introduced.

## Preserved boundaries

- No Bukkit/Paper item replacement worker is activated.
- No physical item, hidden instance UUID, visible template, or applied revision is changed by this PR.
- No natural-encounter listener or chunk force-loading is added.
- No staff command, GUI, pause/resume control, or player-facing editor is wired yet.
- No destructive retirement, purge, deletion, campaign execution, public API expansion, EnthusiaTags integration, deployment, production access, or live-server verification occurred.
- No released migration was edited; V3 is forward-only.
- No temporary diagnostic or branch-mutating workflow was committed.

## Remaining PR 4 requirements

- a bounded Paper `TEMPLATE_UPDATE` executor that locates accessible tracked items, verifies identity, preserves the hidden instance UUID, applies the desired visible template, verifies the result, and advances applied revision only after verified physical success;
- natural-encounter processing for inaccessible tracked items without force-loading chunks or globally scanning inventories;
- bounded staff monitoring and command/GUI wiring for pause/resume, review evidence, retry, and cancellation;
- remaining destructive retirement/deletion workflows and unfinished player-facing rename/editor behavior.

## Exact next work

Begin PR 4c as a separate reviewable pull request: implement the bounded Paper `TEMPLATE_UPDATE` executor and natural-encounter update path on top of the typed queue controls. Preserve hidden instance UUIDs, perform Bukkit access only on the main thread, keep database work asynchronous and bounded, never force-load chunks, and fail ambiguous outcomes into `REVIEW_REQUIRED`. Staff command/GUI wiring may remain a later PR 4 subphase if needed to keep PR 4c reviewable.

## Required prior reports

- `handoffs/0030-2026-08-04-pr6-review-fixes.md`
- `handoffs/0029-2026-08-04-pr6-template-revision-rollout-core.md`
