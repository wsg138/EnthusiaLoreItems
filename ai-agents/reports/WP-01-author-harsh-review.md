# WP-01 full-package harsh review

## Review identity and limitation

This is the completing agent's author-side harsh review of the full WP-01 change set against the package contract and repository-wide production risks. It is supporting evidence, not independent review. CodeRabbit's full pull-request review is the independent review source.

## Scope examined

- Definition browser and definition-specific template-management navigation.
- Read-only management counts, preview, rollout state, pagination, and query bounds.
- Editor session creation, one-session-per-admin enforcement, global bound, chat capture, validation, cancel, timeout, disconnect, reload, shutdown, stale callbacks, and permission removal.
- Every contracted common and specialized edit operation and replace-from-held exact-copy fallback.
- Draft, preview, immutable confirmation, actor/before/after evidence, monotonic revision, duplicate confirmation, callback replay, retry, and restart behavior.
- SQLite atomic revision-plus-rollout persistence, rollback, replay comparison, pagination, recovery, and desired/applied revision state.
- Accessible player, Ender Chest, physical-container, nested shulker/bundle, dropped-item, item-frame, glow-frame, and armor-stand update paths.
- Offline/unloaded deferral and natural-observation handling without force loading.
- Item-loss, duplication, sibling corruption, hidden identity, component preservation, malformed evidence, duplicate conflicts, stale revision, identity mismatch, and ambiguous mutation handling.
- Bukkit/Paper thread boundaries, asynchronous persistence, bounded work/results, reload/shutdown ownership, architecture boundaries, permissions, tab completion, and degraded storage.
- Required domain/application, Paper adapter, SQLite integration, rollout, lifecycle, architecture, repository-tool, complexity, and Codacy evidence.

## Confirmed findings and fixes

1. **Operation-specific chat input was incomplete and rejected input could retain partial mutation.** Chat actions now apply through the selected typed operation, validate a clone, and only replace the session draft after acceptance. Focused invalid-input and stale-session lifecycle tests cover the boundary.
2. **Browse access incorrectly required audit permission at multiple layers.** Command routing, tab completion, GUI dispatch, and management loading now permit either audit or edit permission for browsing while evidence routes remain audit-only and revision changes remain edit-only. Edit-only command/completion/GUI tests cover the complete path.
3. **The three-argument command executor advertised administration routes it could not execute.** Completion is now aware of route availability and has regression coverage.
4. **Rollout planner completion callbacks could continue off the Bukkit thread.** Every asynchronous continuation is marshalled through the Bukkit scheduler before downstream work or accessible inventory wakeups. A background-completion regression test verifies scheduler fencing.
5. **The rollout-store interface allowed implementations to discard complete confirmation evidence.** `startConfirmed` is a required contract; the SQLite implementation persists and replay-compares confirmation ID, before template, actor, expected revision, and audit intent atomically.
6. **Confirmation timeout feedback could be fenced out as stale.** Session cleanup now preserves accurate durable-processing feedback without claiming the already-submitted request was cancelled.
7. **Administration startup exceeded the repository complexity threshold.** Worker activation was extracted into a coherent lifecycle helper.
8. **Session tracking used a non-concurrent map while asynchronous chat could read it.** Session and pending-chat tracking use concurrent bounded maps; tests prove duplicate sessions and the global 32-session limit are rejected without revision creation.
9. **A hung management snapshot query could permanently consume a permit.** Management queries have a bounded timeout applied to a dependent future, release permits on completion/failure/timeout, and retain the storage-owned future. A one-permit regression test proves capacity recovers.
10. **Edit-only access remained blocked in the management loader even after command and GUI fixes.** The loader now uses the shared browse authorization rule, with direct lifecycle coverage.
11. **A specialized editor branch compared a local result to itself.** Unsupported actions now throw explicit validation failure; accepted operations return the normalized edited item without a null sentinel.
12. **Codacy reported 55 new issues, then 12 after the first remediation.** Actual complexity findings were refactored into smaller parser helpers; concurrency and correctness findings were fixed; narrowly scoped suppressions remain only for analyzer-inappropriate parser/test patterns. Exact-head Codacy on reviewed source head `7b91ca90eb27574e1fdf0779e02c448f52158f8c` succeeded with zero annotations.

## Review suggestions not converted into code

- Deriving GUI action sets from renderer internals, sharing tiny index helpers across unrelated editors, and replacing current registry access solely to avoid an API deprecation warning were not required for correctness or the WP-01 contract. At the verification boundary, those refactors would increase coupling or compatibility risk without closing a confirmed defect.
- Additional readback tests suggested in the review body duplicate stronger existing codec/component round-trip, draft-operation, preview, held-item, and rollout-preservation tests. No uncovered acceptance criterion was identified.
- The temporary self-modifying workflow mechanism used to obtain a remote checkout was removed from the final source head. It is not part of the package implementation.

## Verification evidence before this checkpoint

- Focused regression tests passed in GitHub-hosted Java 21/Gradle 8.14.3 checkouts before each generated source commit.
- Full `gradle --no-daemon clean check` passed in the checkout that produced reviewed source head `7b91ca90eb27574e1fdf0779e02c448f52158f8c`.
- Repository-tool tests and the new-code complexity gate passed on the prior normal exact code head; the final complexity refactors also passed full Gradle verification before commit.
- Exact-head Codacy on `7b91ca90eb27574e1fdf0779e02c448f52158f8c` succeeded with zero annotations.
- All live inline review threads are resolved and no review is in requested-changes state.

## Remaining verification

The bot-authored source commit did not receive instantiated official CI jobs and was marked `action_required`. This user-authored coordination checkpoint must receive ordinary exact-head GitHub Actions, repository-tool, complexity, and Codacy success. After that, only prospective completion records, their exact-head verification, normal merge, and live-main verification remain.