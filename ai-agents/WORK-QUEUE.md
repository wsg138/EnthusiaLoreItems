# Fixed remaining-work queue

## Queue invariants
Exactly six immutable packages. Live GitHub outranks snapshots. Resume the single unfinished canonical lock before new work. Never split packages or begin the next package in the same completion chat.

| Order | Package | Weight | Status | Dependency / routing |
|---:|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | 20% | COMPLETE | normally merged and verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | 20% | COMPLETE | normally merged and verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | 20% | COMPLETE | normally merged and verified |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | 15% | COMPLETE | normally merged and verified; RC prerelease verified |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | 15% | BLOCKED | successor fixes and exact-head CI/Codacy pass, but the contract-required fresh independent review is externally unavailable because CodeRabbit refused the successor review at its review quota |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | 10% | BLOCKED | blocked until the WP-05 production `v1.0.0` release is verified |

## Progress
- Globally verified completed: 4/6 packages.
- Weighted completed progress: 75%.
- WP-05 receives no global credit while successor review, final-state verification, merge, and production release gates remain incomplete.

## WP-05 canonical lock
- Branch: `agent/wp-05-live-acceptance-release`.
- Continuation PR: #26 — `WP-05: complete live acceptance and release LoreItems`.
- Live `main` at this checkpoint: `82429ec2c4e7309ae6f11adbacab5e8386cc1ece`.
- Exact implementation/evidence head checkpointed here: `945105087318858cea9fdde99adb9853a51c1504`.
- Production `v1.0.0` tag and release remain absent.

## Completed successor evidence — `9451050873...`
- Fresh review of predecessor `6dcf8199...` completed and identified two actionable findings.
- Both findings are implemented: existing releases now require exact tag plus `isDraft=false` and `isPrerelease=false`, with executable draft/prerelease fail-closed regressions; workflow state records were synchronized.
- Canonical CI `31628311153`, job `94220359053`: `completed/success`.
- Successful CI steps include Gradle `clean check`, repository tooling, executable release publication-state behavior, complexity, exact-head Codacy, deterministic profile, release evidence, reproducibility, and artifact publication.
- The prior resolver finding thread was automatically marked addressed by CodeRabbit in commit `9451050...`; the state-record finding is outdated by this corrected checkpoint.

## External review blocker
CodeRabbit automatically attempted to review successor range `6dcf8199...` through `9451050873...` as run `75e28d83-7398-4c46-ace4-91236c296086`, but its summary comment `5261960978` reports `Review limit reached` and says the next review is available in 51 minutes.

The package contract requires a fresh independent review of every code fix. CI success and CodeRabbit commit status cannot substitute for the refused successor review, so WP-05 is correctly `BLOCKED`.

## Remaining boundary
1. After CodeRabbit review capacity materially recovers, obtain a fresh review of exact implementation head `9451050873...` and resolve any new actionable finding.
2. Only after that review is clean, commit prospective WP-05 `COMPLETE` / WP-06 `READY` as the final source-state commit.
3. Verify that final state SHA with exact-head CI/Codacy/review and zero unresolved threads.
4. Re-read live Sentinel policy/manifest/commands and run final-head production startup then restart to required terminal PASS codes.
5. Reconcile current `main`, normally merge PR #26, and verify post-merge main CI.
6. Verify automatic production `v1.0.0` publication from the exact final WP-05 merge with non-draft/non-prerelease state and all required assets/checksums/source binding.
7. Record global completion and stop without beginning WP-06.

## Blocker
Verified external dependency: CodeRabbit refused the required successor review because its PR review quota is exhausted; its GitHub summary reports the next review available in 51 minutes.

## Exact next action
Re-fetch the canonical branch/PR after the stated review-capacity window materially changes. If this checkpoint remains uncontested, request a fresh independent CodeRabbit review of implementation head `9451050873...`. Do not create prospective completion state, merge, or begin WP-06 before that review is terminal clean.
