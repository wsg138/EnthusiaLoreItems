# Handoff 0015 — PR #2 Codacy remediation and merge readiness

Date: 2026-08-02 (America/Indiana/Indianapolis)
Repository: `wsg138/EnthusiaLoreItems`
Pull request: #2 — Foundation and durable core
Branch: `agent/loreitems-pr1-foundation`

## Scope and stop condition

This session remained limited to completing PR #2, **Implementation PR 1 — Foundation and durable core**. It did not begin held-item Creation, Adoption, Direct Delivery execution, Protection, commands, listeners, tracking/reconciliation execution, GUIs, editing, group-file/campaign execution, physical deletion, EnthusiaTags integration, or any later implementation phase.

The report records the implementation and quality evidence immediately before the final documentation-only head is verified. Because a report cannot contain the future check result for the commit that creates the report itself, exact final-head Actions, Codacy, merge, resulting `main`, and branch-cleanup evidence must be added to PR #2's body and the completion response after this report lands.

## Reconciled starting state

Live GitHub state took priority over report 0014:

- starting `main`: `42aac09129c4fbda2756d30ee034c27ed1cf85b4`;
- starting PR #2 head: `a4476d9ff3f01f2a9beda2304ae1d43eca5e0c2f`;
- PR #2 was open, ready for review (`draft=false`), mergeable, and unmerged;
- exact-head Actions run `30782103396`, job `91588685316`, was green;
- no submitted reviews and no unresolved review threads existed;
- the live Codacy PR comment still reported 100 new issues (`32 high`, `68 medium`).

`handoffs/CURRENT.md` contained stale path references. The immutable files actually present and used were:

- `handoffs/0014-2026-08-02-pr2-codec-foundation-completion.md`;
- `handoffs/0013-2026-08-02-pr2-transaction-helper-consolidation.md`;
- `handoffs/0012-2026-08-02-pr2-deleted-marker-verification.md`;
- `handoffs/0003-2026-08-02-pr2-storage-runtime.md`;
- `docs/architecture.md`, not nonexistent root `ARCHITECTURE.md`.

The branch did not contain the named `CONTRIBUTING.md`, `AGENTS.md`, `docs/pr1-review-checklist.md`, or `docs/runtime-compatibility.md`. Their absence was recorded rather than silently substituting invented requirements.

## Codacy evidence retrieval

The following legitimate retrieval paths were attempted:

1. Live PR #2 comments and GitHub status/check metadata.
2. Codacy check-run annotations available through GitHub.
3. Direct Codacy API/CLI feasibility. No Codacy account token was present, so account-authenticated endpoints could not be used.
4. Repository Codacy/analyzer configuration. No `.codacy.yml` or exported finding list existed initially.
5. A temporary, repository-scoped GitHub Actions workflow using only the PR repository's `GITHUB_TOKEN` with `checks: read` and `statuses: read`. It exported the Codacy check runs and every annotation GitHub made available, then was removed.

The diagnostic head `ae622d42a5fe5c60bddd9b842a5a1f368b9ac4d3` produced export run `30782885028`, job `91590864521`, artifact `8844275715`. Codacy's check title reported 133 new issues at that temporary head. GitHub exposed 50 complete annotations; 83 additional aggregate issues were not exposed individually and were not guessed or classified.

Codacy annotations did not expose native Codacy rule IDs or native per-finding severity. The table therefore records the exact GitHub annotation level (`warning`) and an inferred rule label from the exact analyzer message. Inferred labels are marked with `*`.

### Retrieved finding classifications

- Legitimate: 41
- False positive: 3
- Inapplicable analyzer rule: 4
- Analyzer/context mismatch: 1
- Analyzer configuration problem: 1
- Unretrieved aggregate-only findings: 83, not individually classified

| # | File | Line | Rule | Available severity | Exact message | Classification | Disposition |
| ---: | --- | ---: | --- | --- | --- | --- | --- |
| 1 | `adapters-paper/build.gradle.kts` | 5 | `Gitleaks generic-api-key`* | `warning` | A gitleaks generic-api-key was detected which attempts to identify hard-coded credentials. | False positive | Public Maven dependency coordinate moved behind the shared `paperApiVersion` property; no credential existed. |
| 2 | `adapters-paper/build.gradle.kts` | 6 | `Gitleaks generic-api-key`* | `warning` | A gitleaks generic-api-key was detected which attempts to identify hard-coded credentials. | False positive | Public Maven dependency coordinate moved behind the shared `paperApiVersion` property; no credential existed. |
| 3 | `adapters-paper/src/main/java/net/enthusia/loreitems/paper/PaperItemIdentityCodec.java` | 77 | `PMD CyclomaticComplexity`* | `warning` | Method PaperItemIdentityCodec::readIdentity has a cyclomatic complexity of 13 (limit is 8) | Legitimate | Method decomposed and branch logic made explicit. |
| 4 | `adapters-paper/src/main/java/net/enthusia/loreitems/paper/PaperItemIdentityCodec.java` | 77 | `PMD NPathComplexity`* | `warning` | The method 'readIdentity(ItemStack)' has an NPath complexity of 960, current threshold is 200 | Legitimate | Method decomposed into bounded validation helpers. |
| 5 | `adapters-paper/src/main/java/net/enthusia/loreitems/paper/PaperItemIdentityCodec.java` | 157 | `PMD AvoidLiteralsInIfCondition`* | `warning` | Avoid using Literals in Conditional Statements | Legitimate | Named constants replace repeated numeric or string literals in conditionals. |
| 6 | `adapters-paper/src/test/java/net/enthusia/loreitems/paper/PaperItemIdentityCodecTest.java` | 35 | `PMD UnusedPrivateField`* | `warning` | Avoid unused private fields such as 'REVISION_KEY'. | Legitimate | Unused test field removed. |
| 7 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/BoundedDatabaseExecutor.java` | 17 | `PMD DoNotUseThreads`* | `warning` | To be compliant to J2EE, a webapp should not use any thread. | Inapplicable analyzer rule | Narrow class-level suppression retained only for the intentionally bounded Paper-plugin database worker, with justification. |
| 8 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/BoundedDatabaseExecutor.java` | 23 | `PMD AvoidLiteralsInIfCondition`* | `warning` | Avoid using Literals in Conditional Statements | Legitimate | Named constants replace repeated numeric or string literals in conditionals. |
| 9 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/BoundedDatabaseExecutor.java` | 28 | `PMD DoNotUseThreads`* | `warning` | To be compliant to J2EE, a webapp should not use any thread. | Inapplicable analyzer rule | Narrow class-level suppression retained only for the intentionally bounded Paper-plugin database worker, with justification. |
| 10 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/BoundedDatabaseExecutor.java` | 32 | `PMD DoNotUseThreads`* | `warning` | To be compliant to J2EE, a webapp should not use any thread. | Inapplicable analyzer rule | Narrow class-level suppression retained only for the intentionally bounded Paper-plugin database worker, with justification. |
| 11 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/BoundedDatabaseExecutor.java` | 69 | `PMD DoNotUseThreads`* | `warning` | To be compliant to J2EE, a webapp should not use any thread. | Inapplicable analyzer rule | Narrow class-level suppression retained only for the intentionally bounded Paper-plugin database worker, with justification. |
| 12 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/BoundedDatabaseExecutor.java` | 93 | `PMD AvoidCatchingGenericException`* | `warning` | A catch statement should never catch throwable since it includes errors. | Legitimate | Exception and Error paths are handled separately so fatal errors are not silently treated as normal task failures. |
| 13 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/MigrationRunner.java` | 66 | `PMD UseProperClassLoader`* | `warning` | In J2EE, getClassLoader() might not work as expected.  Use Thread.currentThread().getContextClassLoader() instead. | Analyzer/context mismatch | Replaced class-loader lookup with `MigrationRunner.class.getResourceAsStream`, avoiding the J2EE-specific warning without suppression. |
| 14 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteAnomalyRepository.java` | 51 | `PMD AvoidDuplicateLiterals`* | `warning` | The String literal "anomalyId" appears 4 times in this file; the first occurrence is on line 51 | Legitimate | Repeated literal extracted to a named constant or shared SQL fragment. |
| 15 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteAuditRepository.java` | 104 | `PMD AvoidLiteralsInIfCondition`* | `warning` | Avoid using Literals in Conditional Statements | Legitimate | Named constants replace repeated numeric or string literals in conditionals. |
| 16 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDefinitionRepository.java` | 57 | `PMD AvoidDuplicateLiterals`* | `warning` | The String literal "definitionId" appears 6 times in this file; the first occurrence is on line 57 | Legitimate | Repeated literal extracted to a named constant or shared SQL fragment. |
| 17 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDefinitionRepository.java` | 169 | `PMD AvoidLiteralsInIfCondition`* | `warning` | Avoid using Literals in Conditional Statements | Legitimate | Named constants replace repeated numeric or string literals in conditionals. |
| 18 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDeletedDefinitionMarkerRepository.java` | 49 | `PMD AvoidLiteralsInIfCondition`* | `warning` | Avoid using Literals in Conditional Statements | Legitimate | Named constants replace repeated numeric or string literals in conditionals. |
| 19 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDirectDeliveryRepository.java` | 36 | `PMD AvoidDuplicateLiterals`* | `warning` | The String literal "now" appears 4 times in this file; the first occurrence is on line 36 | Legitimate | Repeated literal extracted to a named constant or shared SQL fragment. |
| 20 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDirectDeliveryRepository.java` | 55 | `PMD AvoidLiteralsInIfCondition`* | `warning` | Avoid using Literals in Conditional Statements | Legitimate | Named constants replace repeated numeric or string literals in conditionals. |
| 21 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDirectDeliveryRepository.java` | 139 | `PMD ExcessiveMethodLength`* | `warning` | Method SQLiteDirectDeliveryRepository::acceptExternal has 52 lines of code (limit is 50) | Legitimate | Method split into focused helpers while preserving transaction boundaries. |
| 22 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDirectDeliveryRepository.java` | 226 | `PMD AvoidLiteralsInIfCondition`* | `warning` | Avoid using Literals in Conditional Statements | Legitimate | Named constants replace repeated numeric or string literals in conditionals. |
| 23 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDistributionCampaignRepository.java` | 103 | `PMD AvoidDuplicateLiterals`* | `warning` | The String literal "now" appears 4 times in this file; the first occurrence is on line 103 | Legitimate | Repeated literal extracted to a named constant or shared SQL fragment. |
| 24 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDistributionCampaignRepository.java` | 216 | `PMD AvoidLiteralsInIfCondition`* | `warning` | Avoid using Literals in Conditional Statements | Legitimate | Named constants replace repeated numeric or string literals in conditionals. |
| 25 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDistributionCampaignRepository.java` | 285 | `PMD AvoidLiteralsInIfCondition`* | `warning` | Avoid using Literals in Conditional Statements | Legitimate | Named constants replace repeated numeric or string literals in conditionals. |
| 26 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDistributionRecipientRepository.java` | 1 | `PMD ExcessiveClassLength`* | `warning` | File adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDistributionRecipientRepository.java has 563 non-comment lines of code | Legitimate | Recipient SQL/binding support extracted to `SQLiteDistributionRecipientSupport`. |
| 27 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDistributionRecipientRepository.java` | 38 | `PMD AvoidDuplicateLiterals`* | `warning` | The String literal "campaignId" appears 10 times in this file; the first occurrence is on line 38 | Legitimate | Repeated literal extracted to a named constant or shared SQL fragment. |
| 28 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDistributionRecipientRepository.java` | 61 | `PMD AvoidDuplicateLiterals`* | `warning` | The String literal "recipientKey" appears 6 times in this file; the first occurrence is on line 61 | Legitimate | Repeated literal extracted to a named constant or shared SQL fragment. |
| 29 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDistributionRecipientRepository.java` | 97 | `PMD AvoidDuplicateLiterals`* | `warning` | The String literal "state" appears 4 times in this file; the first occurrence is on line 97 | Legitimate | Repeated literal extracted to a named constant or shared SQL fragment. |
| 30 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDistributionRecipientRepository.java` | 136 | `PMD UseConcurrentHashMap`* | `warning` | If you run in Java5 or newer and have concurrent access, you should use the ConcurrentHashMap implementation | False positive | Narrow method-level suppression retained because the map is method-local and thread-confined, with justification. |
| 31 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDistributionRecipientRepository.java` | 170 | `PMD AvoidDuplicateLiterals`* | `warning` | The String literal "now" appears 10 times in this file; the first occurrence is on line 170 | Legitimate | Repeated literal extracted to a named constant or shared SQL fragment. |
| 32 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDistributionRecipientRepository.java` | 179 | `PMD AvoidDuplicateLiterals`* | `warning` | The String literal "WHERE campaign_id = ? AND recipient_key = ? " appears 5 times in this file; the first occurrence is on line 179 | Legitimate | Repeated literal extracted to a named constant or shared SQL fragment. |
| 33 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDistributionRecipientRepository.java` | 182 | `PMD AvoidDuplicateLiterals`* | `warning` | The String literal "AND EXISTS (SELECT 1 FROM distribution_campaigns campaign " appears 5 times in this file; the first occurrence is on line 182 | Legitimate | Repeated literal extracted to a named constant or shared SQL fragment. |
| 34 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDistributionRecipientRepository.java` | 183 | `PMD AvoidDuplicateLiterals`* | `warning` | The String literal "WHERE campaign.campaign_id = distribution_recipients.campaign_id " appears 5 times in this file; the first occurrence is on line 183 | Legitimate | Repeated literal extracted to a named constant or shared SQL fragment. |
| 35 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDistributionRecipientRepository.java` | 216 | `PMD AvoidLiteralsInIfCondition`* | `warning` | Avoid using Literals in Conditional Statements | Legitimate | Named constants replace repeated numeric or string literals in conditionals. |
| 36 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDistributionRecipientRepository.java` | 332 | `PMD AvoidDuplicateLiterals`* | `warning` | The String literal "next_attempt_at = NULL, updated_at = ? " appears 4 times in this file; the first occurrence is on line 332 | Legitimate | Repeated literal extracted to a named constant or shared SQL fragment. |
| 37 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDistributionRecipientRepository.java` | 386 | `PMD CyclomaticComplexity`* | `warning` | Method SQLiteDistributionRecipientRepository::validateInitialRecipient has a cyclomatic complexity of 10 (limit is 8) | Legitimate | Method decomposed and branch logic made explicit. |
| 38 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDistributionRecipientRepository.java` | 416 | `PMD AvoidLiteralsInIfCondition`* | `warning` | Avoid using Literals in Conditional Statements | Legitimate | Named constants replace repeated numeric or string literals in conditionals. |
| 39 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDistributionRecipientRepository.java` | 450 | `PMD ExcessiveMethodLength`* | `warning` | Method SQLiteDistributionRecipientRepository::claimPending has 68 lines of code (limit is 50) | Legitimate | Method split into focused helpers while preserving transaction boundaries. |
| 40 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDistributionRecipientRepository.java` | 499 | `PMD AvoidLiteralsInIfCondition`* | `warning` | Avoid using Literals in Conditional Statements | Legitimate | Named constants replace repeated numeric or string literals in conditionals. |
| 41 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDistributionRecipientRepository.java` | 585 | `PMD AvoidLiteralsInIfCondition`* | `warning` | Avoid using Literals in Conditional Statements | Legitimate | Named constants replace repeated numeric or string literals in conditionals. |
| 42 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteInstanceRepository.java` | 147 | `PMD AvoidLiteralsInIfCondition`* | `warning` | Avoid using Literals in Conditional Statements | Legitimate | Named constants replace repeated numeric or string literals in conditionals. |
| 43 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteObservationRepository.java` | 31 | `PMD AvoidLiteralsInIfCondition`* | `warning` | Avoid using Literals in Conditional Statements | Legitimate | Named constants replace repeated numeric or string literals in conditionals. |
| 44 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteObservationRepository.java` | 62 | `PMD AvoidLiteralsInIfCondition`* | `warning` | Avoid using Literals in Conditional Statements | Legitimate | Named constants replace repeated numeric or string literals in conditionals. |
| 45 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLitePendingMutationRepository.java` | 76 | `PMD AvoidLiteralsInIfCondition`* | `warning` | Avoid using Literals in Conditional Statements | Legitimate | Named constants replace repeated numeric or string literals in conditionals. |
| 46 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLitePendingMutationRepository.java` | 176 | `PMD ExcessiveMethodLength`* | `warning` | Method SQLitePendingMutationRepository::claimPending has 53 lines of code (limit is 50) | Legitimate | Method split into focused helpers while preserving transaction boundaries. |
| 47 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteUnitOfWork.java` | 43 | `PMD AvoidFieldNameMatchingMethodName`* | `warning` | Field definitions has the same name as a method | Legitimate | Backing fields renamed to distinguish repository fields from accessor methods. |
| 48 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteUnitOfWork.java` | 44 | `PMD AvoidFieldNameMatchingMethodName`* | `warning` | Field deletedDefinitionMarkers has the same name as a method | Legitimate | Backing fields renamed to distinguish repository fields from accessor methods. |
| 49 | `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteUnitOfWork.java` | 46 | `PMD AvoidFieldNameMatchingMethodName`* | `warning` | Field audit has the same name as a method | Legitimate | Backing fields renamed to distinguish repository fields from accessor methods. |
| 50 | `adapters-sqlite/src/main/resources/db/migration/V1__foundation.sql` | 50 | `SQL parser / dialect validation`* | `warning` | syntax error at or near "AUTOINCREMENT" | Analyzer configuration problem | Repository SQLFluff configuration now explicitly selects SQLite; migration SQL remains valid SQLite. |

## Files and rules remediated

The first focused remediation changed only files implicated by retrieved evidence or needed to preserve behavior while splitting those files:

- `gradle.properties`, `adapters-paper/build.gradle.kts`, `plugin/build.gradle.kts` — shared Paper API version property; removed false secret signatures without hiding dependencies;
- `.sqlfluff` — explicit SQLite dialect;
- `PaperItemIdentityCodec.java` and its test — decomposed identity parsing, named constants, removed unused field;
- `BoundedDatabaseExecutor.java` — named constants, narrow J2EE-thread suppression, separate `Exception`/`Error` handling;
- `MigrationRunner.java` — class-relative resource loading and defensive transaction restoration;
- SQLite repositories listed in the findings — extracted repeated column/SQL constants and decomposed oversized methods;
- `SQLiteDistributionRecipientSupport.java` — extracted recipient SQL/binding support from the oversized repository;
- `SQLitePendingMutationRepository.java` — reused the shared transaction helper rather than retaining duplicate rollback logic;
- `SQLiteUnitOfWork.java` — renamed backing fields that collided with method names.

Codacy subsequently reported **Up to standards — 0 new issues** on exact head `7a2a8dbd34470da25fcce331665aafe96ba40884`.

## Suppressions

Only two narrow, documented suppressions were added:

1. `@SuppressWarnings("PMD.DoNotUseThreads")` on `BoundedDatabaseExecutor`, because this is a Paper plugin rather than a J2EE web application and the single database worker plus bounded queue is an explicit architecture requirement.
2. `@SuppressWarnings("PMD.UseConcurrentHashMap")` on the method that creates a method-local, thread-confined `EnumMap`; concurrent access is impossible by construction.

No analyzer was disabled broadly. No warning threshold, compiler warning, architecture rule, transaction guarantee, bounded-work limit, or test was weakened.

## Separate harsh full-PR review

The full PR was independently reviewed for item loss, duplicate delivery, idempotency, transaction rollback, stale claims, lifecycle races, main-thread blocking, unbounded work, retained mutable Bukkit references, migration safety, and phase leakage.

Confirmed defects and fixes:

1. **Forced database shutdown could abandon returned futures forever.** `shutdownNow()` discarded queued tasks without completing their `CompletableFuture`s. Queued tasks are now represented by rejectable wrappers, and forced shutdown completes every abandoned future exceptionally. Regression test: `BoundedDatabaseExecutorTest.forcedShutdownCompletesAbandonedQueuedFutures`.
2. **Queued configuration reloads could remain incomplete during disable.** Pending reload stages are now tracked and completed with a not-applied result when shutdown begins.
3. **Startup or reload could publish state after disable began.** Lifecycle publication and shutdown are now serialized. The writable service, storage runtime, and reloaded configuration cannot be published after the plugin has entered stopping state.
4. **Expired direct-delivery and pending-mutation claim recovery was unbounded.** Both repository APIs now require a positive limit, select rows in deterministic order, update only one bounded batch, and have tests proving subsequent batches remain for later recovery. Startup uses `deliveryClaimBatchSize`.
5. **Duplicate transaction helper logic could diverge from rollback guarantees.** Pending-mutation writes now use the shared `SQLiteTransactions` helper, whose rollback and auto-commit restoration behavior was hardened.

No additional confirmed merge blocker was found. Physical inventory insertion is intentionally outside this phase, so no physical item was delivered or protected in this PR.

## Verification evidence before final report commit

### Local

An exact source/runtime bundle for `edc3a52a3d89acdb8a1bca0461edcc5ff63e339f` was exported by run `30784414687`, job `91595145577`, artifact `8844771822` (artifact digest `sha256:db3ebc146863363310db3b887ef18c278cd1ee1d8c00736a3f8235d85f11e8a5`). The bundle contained Gradle 8.14.3, its isolated dependency cache, and the exact source archive.

The same build was then run locally with networking disabled and task outputs forced to execute:

```text
gradle --offline --no-daemon clean check --rerun-tasks
BUILD SUCCESSFUL in 14s
34 actionable tasks: 34 executed
```

### GitHub Actions

Exact head `edc3a52a3d89acdb8a1bca0461edcc5ff63e339f` passed run `30784414682`, job `91595182954`. The job checked out merge ref `1b2179423681d403bc92b0fd5fb5357ac6e898d8`, which merged that exact head into base `42aac09129c4fbda2756d30ee034c27ed1cf85b4`, and ran:

```text
gradle --no-daemon clean check
BUILD SUCCESSFUL in 1m 15s
34 actionable tasks: 26 executed, 8 up-to-date
```

The only workflow messages were GitHub-hosted action Node-runtime deprecation warnings; no build or test task failed.

The temporary local-bundle workflow was removed at `96f49d189501990dd2864792ef41139bc1089f1b`. It did not alter production or test source.

## Review and live-server status

- Submitted pull-request reviews before this report: none.
- Unresolved review threads before this report: none.
- CodeRabbit produced no code finding; it skipped because the PR exceeded its 100-file limit and review capacity was unavailable.
- No live Paper/Leaf server was started. Real-server PDC/item serialization, restart/reload behavior, corrupt-database recovery, backup/restore, and future physical delivery remain unverified and must not be claimed.

## Merge readiness and exact next action

After this report, `handoffs/CURRENT.md`, and `handoffs/INDEX.md` are committed, perform one final exact-head verification:

1. GitHub Actions must pass on the documentation head.
2. Codacy must report zero new issues/up to standards on that exact head.
3. No submitted review or unresolved valid thread may remain.
4. PR #2 must remain ready and mergeable.

When those conditions are directly verified, merge PR #2 with a normal merge commit, verify the resulting `main` SHA and checks, update the PR body with the merge evidence, delete the feature branch if the connector permits it, and stop. Do not begin a later phase in this session.
