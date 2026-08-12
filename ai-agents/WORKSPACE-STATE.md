# Workspace state

## Snapshot warning
Live GitHub is authoritative. Resolve conflicts in this order: live GitHub; selected package contract; workflow docs; requirements; architecture; implementation plan; state/handoffs.

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Continuation PR: #26, `WP-05: complete live acceptance and release LoreItems`.
- Live `main` at the fresh-review checkpoint: `82429ec2c4e7309ae6f11adbacab5e8386cc1ece`.
- Exact fresh-review predecessor: `6dcf8199cc8643b961d42f9cb36bf5e4d7a63ff5`.
- Exact retained implementation/evidence baseline: `2e8bc340e6e6d012c732889d50026da97f39d675`.
- WP-06 is `BLOCKED`; it remains blocked until production `v1.0.0` is verified. Do not begin WP-06.

## Package registry
| Package | Weight | Status | Reason |
|---|---:|---|---|
| WP-01 | 20% | COMPLETE | normally merged and verified |
| WP-02 | 20% | COMPLETE | normally merged and verified |
| WP-03 | 20% | COMPLETE | normally merged and verified |
| WP-04 | 15% | COMPLETE | normally merged; RC prerelease verified |
| WP-05 | 15% | IN_PROGRESS | fresh independent review is available and returned two actionable continuation findings now being fixed |
| WP-06 | 10% | BLOCKED | blocked until WP-05 production `v1.0.0` is verified |

- Globally verified completed: 4/6 packages.
- Weighted completed progress: 75%.

## Retained package evidence
- Prior WP-05 PR #18 passed the full acceptance matrix, exact-head checks, review/thread gates, and explicit production Sentinel startup/restart, then normally merged as `82429ec2c4e7309ae6f11adbacab5e8386cc1ece`.
- Push-to-main CI `31559889210` succeeded on that merge.
- Automatic production Release run `31560031191` failed before any `v1.0.0` tag/release mutation, returning finalization to this same WP-05 package.
- Continuation implementation baseline `2e8bc340...` fixed explicit-404-only tag lookup handling, non-404 diagnostic/status propagation, executable resolver behavior tests, and privileged `workflow_run` source checkout; CI `31562243246` and exact Codacy `94006943660` passed.

## Fresh independent review
- Resume checkpoint `6dcf8199...` successfully ran canonical CI `31627583672`, job `94217890215`; every required step passed, including exact-head Codacy and the executable publication-state regression.
- Review request comment `5271038247` triggered CodeRabbit run `53b20eba-24bc-43fc-9440-ddf43834fc53` across the previously uncovered continuation range through `6dcf8199...`.
- The review reached terminal success status and posted two actionable findings:
  1. the existing-release resolver branch must query and reject `isDraft=true` or `isPrerelease=true` before emitting `released=true`;
  2. workflow state records must remain mutually consistent while review is pending. The pending review is now terminal, so queue/workspace/handoff are consistently `IN_PROGRESS` while findings are remediated.

## This checkpoint remediation
- Existing-release metadata now includes `tagName`, `isDraft`, and `isPrerelease`; exact tag is still required and both production-state flags must be false.
- Executable resolver tests add draft and prerelease existing-release cases and require both to fail closed with no publication outputs.
- Source-level release contract tests assert the production-state checks.
- No plugin runtime, persistence, schema, public API, Paper behavior, or Floodgate behavior changes.

## Remaining acceptance criteria
1. Exact-head CI/Codacy and the new draft/prerelease regression must pass on this successor.
2. A fresh independent review must cover this successor and finish clean with zero unresolved threads.
3. Then create the required prospective final WP-05 `COMPLETE` / WP-06 `READY` source-state commit.
4. Verify that final state head with exact-head CI/Codacy/review and explicit production Sentinel `startup` then `restart` terminal PASS results.
5. Reconcile live `main`, normally merge PR #26, and verify exact post-merge main CI.
6. Verify automatic production `v1.0.0` targets the exact merge and is non-draft/non-prerelease with every required asset/checksum/source binding.
7. Record durable global completion and stop without beginning WP-06.

## Known findings
The two fresh-review findings are remediated in this checkpoint successor but remain unverified until successor CI/review complete.

## Blocker
None. The CodeRabbit quota recovered and the package is actionable.

## Exact next action
Fast-forward this review-fix checkpoint from exact predecessor `6dcf8199...`, immediately re-fetch the canonical branch/PR, then verify the successor through exact-head CI/Codacy and fresh independent review. Do not begin WP-06.
