# Fixed remaining-work queue

## Queue invariants
Exactly six immutable packages. Live GitHub outranks snapshots. Resume the single unfinished canonical lock before new work. Never split packages or begin the next package in the same completion chat.

| Order | Package | Weight | Status | Dependency / routing |
|---:|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | 20% | COMPLETE | normally merged and verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | 20% | COMPLETE | normally merged and verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | 20% | COMPLETE | normally merged and verified |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | 15% | COMPLETE | normally merged and verified; RC prerelease verified |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | 15% | BLOCKED | continuation implementation and exact-head CI are complete, but the contract-required fresh independent review is externally unavailable because CodeRabbit refused the latest review request at its PR review quota |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | 10% | BLOCKED | blocked until the WP-05 production `v1.0.0` release is verified |

## Progress
- Globally verified completed: 4/6 packages.
- Weighted completed progress: 75%.
- No WP-05 credit is awarded while the required independent review, final-head merge and production release gates remain incomplete.

## WP-05 canonical lock
- Branch: `agent/wp-05-live-acceptance-release`.
- Continuation PR: #26 — `WP-05: complete live acceptance and release LoreItems`.
- Live `main`: `82429ec2c4e7309ae6f11adbacab5e8386cc1ece`.
- Exact implementation/evidence head checkpointed here: `2e8bc340e6e6d012c732889d50026da97f39d675`.
- Prior normal merge PR #18: `82429ec2c4e7309ae6f11adbacab5e8386cc1ece`; push-to-main CI `31559889210` succeeded, but automatic Release run `31560031191` failed twice before tag/release publication.
- Production `v1.0.0` tag/release remain absent.

## Current implementation evidence
Continuation PR #26 fixes the production release publication-state resolver and its regression coverage. Exact head `2e8bc340...`:

- CI `31562243246`, job `94006778747`: `completed/success`.
- Executable shared-resolver regression: success for missing 404, null-success fail-closed, exact-tag recovery, 403/429/500 fail-closed, and existing immutable release behavior.
- Exact Codacy `94006943660`: success, zero annotations.
- Plugin artifact `9128174387`, `enthusialoreitems-plugin`, digest `sha256:21b32ddc058cb67d37bf45f78a759c2342efb8424d8b01a06cff594eed9b6658`.
- Verification artifact `9128173668`, `wp04-verification-2e8bc340e6e6d012c732889d50026da97f39d675`, digest `sha256:8958d98aebc8762aa0c7451cc73aafe967c8c89fcbfa79d9486e0d54e2793615`.
- Production Release workflow no longer uses privileged `workflow_run` checkout; it fetches only the resolver file from the exact successful CI SHA through GitHub's contents API.
- All earlier CodeRabbit inline review threads are resolved/outdated.

## External review blocker
The package contract requires independent review of all code fixes. After exact-head CI passed, a fresh CodeRabbit review was requested on PR #26 by comment `5262169147`. CodeRabbit's updated summary comment `5261960978` explicitly refused the review with `Review limit reached`, identifying the latest review range through `2e8bc340...`.

The older CodeRabbit review/status does not satisfy the exact latest-code independent-review requirement. This is a verified external dependency, so WP-05 is `BLOCKED` rather than `IN_REVIEW`, `VERIFYING`, or prospectively `COMPLETE`.

## Remaining boundary
1. When the independent-review service quota materially recovers, obtain a fresh review of the current implementation delta and resolve any findings.
2. Commit prospective WP-05 `COMPLETE` / WP-06 `READY` as the last source commit only after independent review is clean.
3. Verify that final state SHA with exact-head CI/Codacy/review and explicit production Sentinel startup/restart.
4. Reconcile current `main`, normally merge PR #26, verify post-merge main CI.
5. Verify automatic production `v1.0.0` publication from the exact final WP-05 merge with all required assets/checksums.
6. Record global completion and stop without beginning WP-06.

## Blocker
Verified external dependency: fresh CodeRabbit independent review is unavailable because the service's PR review quota refused the latest review request.

## Exact next action
Re-fetch branch/PR and, only after the review quota materially recovers, request a fresh CodeRabbit review of the current implementation delta. Keep WP-06 blocked and do not write prospective completion state before that review succeeds.
