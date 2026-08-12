# Fixed remaining-work queue

## Queue invariants
Exactly six immutable packages. Live GitHub outranks snapshots. Resume the single unfinished canonical lock before new work. Never split packages or begin the next package in the same completion chat.

| Order | Package | Weight | Status | Dependency / routing |
|---:|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | 20% | COMPLETE | normally merged and verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | 20% | COMPLETE | normally merged and verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | 20% | COMPLETE | normally merged and verified |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | 15% | COMPLETE | normally merged and verified; RC prerelease verified |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | 15% | IN_PROGRESS | successor implementation and exact-head CI/Codacy are complete; prior CodeRabbit quota blocker materially changed and the mandatory fresh successor review is being resumed |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | 10% | BLOCKED | blocked until the WP-05 production `v1.0.0` release is verified |

## Progress
- Globally verified completed: 4/6 packages.
- Weighted completed progress: 75%.
- WP-05 receives no global credit until fresh successor review, final-state verification, normal merge, and production release verification all succeed.

## WP-05 canonical lock
- Branch: `agent/wp-05-live-acceptance-release`.
- Continuation PR: #26 — `WP-05: complete live acceptance and release LoreItems`.
- Resume parent checkpoint: `60bc310ebceb06940052ab9461949a968c9ff2cc`.
- Live `main` at reconciliation: `82429ec2c4e7309ae6f11adbacab5e8386cc1ece`.
- Exact implementation/evidence head being reviewed: `945105087318858cea9fdde99adb9853a51c1504`.
- Production `v1.0.0` tag and release remain absent.

## Completed implementation evidence
- Fresh CodeRabbit review through predecessor `6dcf8199...` found two actionable issues.
- Successor `9451050873...` implements both: existing release recovery requires exact tag plus `isDraft=false` and `isPrerelease=false`, and workflow state was synchronized.
- Executable resolver regressions cover missing/null/exact tag, 403/429/500, valid existing release, draft release, and prerelease release behavior.
- Exact successor CI `31628311153`, job `94220359053`: `completed/success`, including Gradle `clean check`, repository tooling, release-state regression, complexity, exact-head Codacy, deterministic profile, immutable release evidence, reproducibility, and artifact publication.
- Blocker checkpoint `60bc310e...` also passed canonical CI `31628688529`; its dedicated product workflows were path-filtered/skipped.
- All currently visible inline review threads are resolved.

## Resume reason
The previous durable blocker was CodeRabbit quota refusal for successor review. Live reconciliation now shows the bot summary no longer contains the `Review limit reached` warning and the current checkpoint reports CodeRabbit `success`. Those signals do not themselves satisfy the independent-review gate, but they are a material external-condition change and make a new review attempt valid.

## Remaining boundary
1. Obtain a fresh independent review covering the unreviewed successor delta through the current branch head; resolve every actionable finding and require zero unresolved threads.
2. Only after review is terminal clean, commit prospective WP-05 `COMPLETE` / WP-06 `READY` as the final source-state commit.
3. Verify that final source head with exact-head CI/Codacy/review and zero unresolved threads.
4. Re-read live Sentinel policy, exact-head manifest, LoreItems staging docs, and Staff-Staging command contract; run final-head production `startup` then `restart` to terminal PASS codes.
5. Reconcile current `main`; if it moved, merge current `main` non-destructively and repeat stale exact-head gates.
6. Normally merge PR #26 with GitHub merge-commit method only, then verify post-merge `main` CI.
7. Verify automatic production `v1.0.0` targets the exact WP-05 merge, is non-draft/non-prerelease, and contains every required asset/checksum/source binding.
8. Record durable global completion and stop without beginning WP-06.

## Blocker
None while the materially changed independent-review condition is actively being rechecked.

## Exact next action
Fast-forward this resume checkpoint from exact observed parent `60bc310e...`, re-fetch branch/PR for concurrency safety, then request a fresh CodeRabbit review. If the service again refuses the review, return WP-05 to `BLOCKED` with the new GitHub evidence. If review runs, resolve findings and continue WP-05 finalization. Do not begin WP-06.
