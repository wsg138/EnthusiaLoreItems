# Current development handoff

## Active work

- Phase: Implementation PR 1 — Foundation and durable core
- Repository: `wsg138/EnthusiaLoreItems`
- Pull request: #2 — Foundation and durable core
- Branch: `agent/loreitems-pr1-foundation`
- Status: blocked on detailed evidence for Codacy's remaining 35 new issues; do not merge

Always reconcile this handoff with live GitHub. Obtain the current `main` SHA, PR head, ready/draft state, mergeability, exact-head GitHub Actions, Codacy result, submitted reviews, unresolved threads, and comments newer than the latest immutable report.

## Latest report

- [`0016-2026-08-02-pr2-codacy-evidence-blocker.md`](0016-2026-08-02-pr2-codacy-evidence-blocker.md)

Report 0016 records the later 85-issue, 48-issue, and 35-issue Codacy passes, every retrieval method attempted, all remediation completed from retrieved records, exact suppressions, harsh-review fixes, verification evidence, and the precise owner export required.

## Required prior reports

- [`0015-2026-08-02-pr2-codacy-remediation-and-merge-readiness.md`](0015-2026-08-02-pr2-codacy-remediation-and-merge-readiness.md) — first 50 detailed Codacy records and classifications.
- [`0014-2026-08-02-pr2-codec-foundation-completion.md`](0014-2026-08-02-pr2-codec-foundation-completion.md) — complete codec-foundation scope and pre-Codacy exact-head baseline.
- [`0013-2026-08-02-pr2-transaction-helper-consolidation.md`](0013-2026-08-02-pr2-transaction-helper-consolidation.md) — persistence transaction consolidation.
- [`0012-2026-08-02-pr2-deleted-marker-verification.md`](0012-2026-08-02-pr2-deleted-marker-verification.md) — preceding exact-head persistence baseline.
- [`0003-2026-08-02-pr2-storage-runtime.md`](0003-2026-08-02-pr2-storage-runtime.md) — bounded database executor, connection ownership, idempotency, claim fencing, and transaction rules.

The architecture document present in this repository is [`../docs/architecture.md`](../docs/architecture.md). Do not follow stale references to nonexistent root `ARCHITECTURE.md`, `CONTRIBUTING.md`, `AGENTS.md`, `docs/pr1-review-checklist.md`, or `docs/runtime-compatibility.md` unless those files are added later and verified live.

## Exact next step

Do not implement anything else. Do not begin Implementation PR 2 or any Creation, Adoption, Direct Delivery execution, Protection, command, listener, tracking/reconciliation, GUI, editing, group-file/campaign, physical deletion, or Tags-integration work.

For PR #2 only:

1. Obtain the remaining 35 Codacy records with issue ID, file, line, tool, rule/pattern, severity, category, and full message. The accepted export/API methods are specified in report 0016.
2. Classify every supplied record and fix every legitimate in-scope issue. Add only narrow documented suppressions for genuine false positives.
3. Run full local Gradle checks.
4. Push to the existing branch and require exact-head GitHub Actions and Codacy success.
5. Re-read submitted reviews, unresolved threads, and newer comments.
6. Update the immutable handoff, this file, `INDEX.md`, and PR body with final evidence.
7. Only when every gate is directly green, merge with a normal merge commit, verify `main`, delete the branch if supported, and stop.

If detailed findings remain unavailable, do not guess and do not merge.

## Known limitations

- CodeRabbit produced no code finding because the PR exceeded its file limit and review capacity was unavailable.
- No live Paper/Leaf server was started. Do not claim live-server, real-server PDC serialization, backup/restore, corrupt-database recovery, or physical inventory-delivery evidence.
