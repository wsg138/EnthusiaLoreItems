# Fixed remaining-work queue

## Queue invariants
Exactly six immutable packages. Live GitHub outranks snapshots. Resume the single unfinished canonical lock before new work. Never split packages or begin the next package in the same completion chat.

| Order | Package | Weight | Status | Dependency |
|---:|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | 20% | COMPLETE | merged/verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | 20% | COMPLETE | merged/verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | 20% | COMPLETE | PR #14 normally merged; live merge and post-merge Actions verified |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | 15% | COMPLETE | PR #15 normally merged; release-recovery PR #16 normally merged; post-merge CI and `v1.0.0-rc.1` prerelease/assets verified |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | 15% | BLOCKED | draft PR #18; external designated live acceptance server/test-account access is unavailable to this worker and no executed GitHub-backed matrix evidence exists |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | 10% | BLOCKED | WP-05 production release |

## WP-04 completion record
- Production-hardening PR: #15, normally merged as `89399db2d92fd7197479a8803e920c02f5bec490`.
- Release-recovery PR: #16, normally merged as `e4b7968adea1357e7307815a5a5ef7f456f16ad1`.
- Exact PR-head verification for final WP-04 head `063ad63ee7341cc42a4f20c51883d5c34abd25a7`: Actions run `31204122398` passed Gradle verification, repository tooling, new-code complexity, exact-head Codacy, fixed-scenario profiling, RC package validation, immutable evidence packaging, and clean-build reproducibility; CodeRabbit status was successful and review threads were zero.
- Post-merge WP-04 `main` verification: CI run `31204427939` passed on `89399db2d92fd7197479a8803e920c02f5bec490`.
- Release-recovery exact-head verification: Actions run `31204825737` passed on `c0be8bf9755e7038f6a8a9f1feb715322136f3a4`, including exact-head Codacy; CodeRabbit was successful and review threads were zero.
- Post-recovery `main` verification: CI run `31205231097` passed on `e4b7968adea1357e7307815a5a5ef7f456f16ad1`.
- Release workflow run `31205326905` completed successfully and recovered the already-created exact RC tag without moving it.
- `v1.0.0-rc.1` is a GitHub prerelease targeting `89399db2d92fd7197479a8803e920c02f5bec490`.
- Verified release assets: `EnthusiaLoreItems.jar`, `EnthusiaLoreItems.jar.sha256`, `bom.cyclonedx.json`, `gradle-dependencies.txt`, `normalized-entry-manifest.txt`, `wp04-profile.json`, and `EnthusiaLoreItems-test-reports.tar.gz`.
- The released JAR digest reported by GitHub is `sha256:3c7b6aa74ee63a4e049c5e09f2bebffe78bf50ea88caaaa3d03b55e941f427c8`.
- No live Paper/Leaf acceptance is claimed by WP-04; that remains WP-05 scope.

## WP-05 durable blocker checkpoint
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`.
- Canonical branch: `agent/wp-05-live-acceptance-release`.
- Draft PR: #18, `WP-05: complete live acceptance and release LoreItems`.
- Durable claim commit: `760f04f162b934d7a0f21ba8c354548aeb8cffbf`.
- IN_PROGRESS coordination checkpoint: `5825c2ddc284300ec323a47d5d62b6bb9a8ac853`.
- Exact RC under test when unblocked: `v1.0.0-rc.1`, JAR SHA-256 `3c7b6aa74ee63a4e049c5e09f2bebffe78bf50ea88caaaa3d03b55e941f427c8`.
- Completed WP-05 acceptance criteria: none. Routing, claim, RC metadata verification, and blocker verification are not live acceptance.
- External blocker: the package requires deployment to and operation of a designated Java 21 Paper/Leaf 1.21.11 server with Geyser/Floodgate plus Java, Bedrock, offline, and never-joined test accounts. This worker has no connected remote-server/SSH capability, repository-supplied server access, or installable matching connector, and repository/issue search found no already-executed case evidence.
- Resume condition: make the designated acceptance environment and required test accounts operable by this worker, or provide durable GitHub-backed exact-RC case evidence that can be independently audited. Resume the same branch/PR; do not create another package.

## Progress
- Completed: 4/6
- Remaining: 2/6
- Weighted progress: 75%

## Exact next action
Resume WP-05 on PR #18 when the external live-acceptance dependency is available. Reconcile live GitHub first, then execute `ACC-ENV-001` against the exact RC and continue the entire matrix. Do not begin WP-06.
