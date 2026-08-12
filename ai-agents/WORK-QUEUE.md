# Fixed remaining-work queue

## Queue invariants
Exactly six immutable packages. Live GitHub outranks snapshots. Resume the single unfinished canonical lock before new work. Never split packages or begin the next package in the same completion chat.

| Order | Package | Weight | Status | Dependency / routing |
|---:|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | 20% | COMPLETE | normally merged and verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | 20% | COMPLETE | normally merged and verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | 20% | COMPLETE | normally merged and verified |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | 15% | COMPLETE | normally merged and verified; RC prerelease verified |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | 15% | IN_PROGRESS | continuation PR #26 is correcting the confirmed post-merge production-release resolver defect and independent-review findings |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | 10% | BLOCKED | blocked until the WP-05 production `v1.0.0` release is verified |

## Progress
- Globally verified completed: 4/6 packages.
- Weighted completed progress: 75%.
- WP-05 remains incomplete until continuation PR #26 is verified/merged and production `v1.0.0` is verified.

## WP-05 continuation lock
- Canonical branch: `agent/wp-05-live-acceptance-release`.
- Continuation PR: #26 — `WP-05: complete live acceptance and release LoreItems`.
- Prior normal merge: `82429ec2c4e7309ae6f11adbacab5e8386cc1ece`.
- Exact predecessor implementation/review head for this checkpoint: `674426d7ba767ff8ef3657d799705145fe0291ca`.
- Live `main`: `82429ec2c4e7309ae6f11adbacab5e8386cc1ece`.
- Push-to-main CI `31559889210`: success.
- Production Release run `31560031191`: failed twice before tag/release creation; jobs `94000290257` and `94000725832` both failed `Resolve publication state`.
- `v1.0.0` tag: absent.
- `v1.0.0` release: absent.

## Continuation verification through `674426d7...`
- Canonical CI `31560798712`: success.
- Exact plugin artifact `9127660940`, `enthusialoreitems-plugin`, digest `sha256:9e294d4f4439471b093ddb85fa3a189b3996e8f90bb1ae56cfab0c9b50aae156`.
- Exact verification artifact `9127660626`, `wp04-verification-674426d7ba767ff8ef3657d799705145fe0291ca`, digest `sha256:218f3771c21254b5eecab16252dfc829d9107e8f68a8e5c4afc62739e77e9dfd`.
- Exact Codacy `94002608794`: success, zero annotations.
- Automatic ready-transition Sentinel `94002811226`: `PAPER_SMOKE_OK` on exact predecessor; supporting evidence only.
- CodeRabbit completed review of PR #26 and raised three actionable threads. This checkpoint successor addresses them:
  - only explicit HTTP 404 from the tag-ref lookup can mean “tag absent”; every other failed lookup re-emits diagnostics and fails with its original status;
  - WP-06 dependency wording now states it is blocked until production `v1.0.0` is verified;
  - queue/workspace/handoff records now identify PR #26, the exact predecessor evidence, completed criteria, remaining gates, blocker state and exact next action.
- Regression coverage asserts preserved API-status handling, explicit 404-only fallthrough, non-404 fail-closed diagnostics/status propagation, exact-tag recovery, main-SHA binding and existing-release exact-tag/asset validation.

## Remaining boundary
1. Fresh exact-head CI/Codacy/artifacts/review on this checkpoint successor; confirm and resolve the three review threads.
2. Required prospective final WP-05 `COMPLETE` / WP-06 `READY` state commit as the last source commit, followed by fresh final-head verification.
3. Explicit final-head Sentinel startup `PAPER_SMOKE_OK` then sequential restart `PAPER_RESTART_OK`.
4. Current-main reconciliation and normal merge of PR #26.
5. Exact post-merge `main` CI and automatic production `v1.0.0` tag/release/assets/checksum verification.
6. Durable global completion record; then stop without beginning WP-06.

## Blocker
None.

## Exact next action
Fast-forward the canonical branch from exact predecessor `674426d7...` with this review-fix checkpoint, immediately re-fetch the branch/PR, and verify the successor's exact-head gates. WP-06 remains blocked.
