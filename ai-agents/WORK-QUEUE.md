# Fixed remaining-work queue

## Queue invariants

This queue contains exactly six fixed work packages. Package identity, order, dependencies, weight, scope, acceptance criteria, branch name, and PR title are immutable. Workers may update status, evidence, counts, and progress under the committed rules but may not split, merge, rename, reorder, or redefine a package.

Live GitHub outranks this snapshot. The universal dispatcher automatically resumes or selects exactly one package.

## Ordered queue

| Order | Package | Fixed objective | Weight | Status | Exact dependency |
|---:|---|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | Complete the editor and template-management interface. | 20% | COMPLETE | Final records verification, normal merge, and live-main confirmation are the completing agent's remaining duties |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | Complete destructive administration and queued-operation controls. | 20% | READY | WP-01 prospective COMPLETE; selection permitted only after authoritative merge verification and in a later chat |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | Complete one-use mass distributions. | 20% | BLOCKED | WP-02 COMPLETE |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | Complete automated production hardening and produce a release candidate. | 15% | BLOCKED | WP-03 COMPLETE |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | Process manual live-server acceptance evidence, fix every confirmed defect, and release EnthusiaLoreItems. | 15% | BLOCKED | WP-04 release candidate published |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | Complete the separate EnthusiaTags service-API integration after LoreItems is released. | 10% | BLOCKED | WP-05 production release published |

## Completion lock and evidence

- Completing package: WP-01
- Branch: `agent/wp-01-editor-template-management`
- Pull request: #11, `WP-01: complete editor and template management`
- Starting branch SHA for this session: `f974c2d23a488d0e08d0902a37929e69e0456a57`
- Reviewed source head: `7b91ca90eb27574e1fdf0779e02c448f52158f8c`
- Verified pre-completion checkpoint: `22a28078f25b5e24aa6c611f6dff06ab504a4267`
- GitHub Actions run `31073464520`: Gradle, repository tools, complexity, and exact-head Codacy all passed.
- Review state: all inline threads resolved; no requested-changes review.
- Status: prospective `COMPLETE` until this exact records commit is verified and normally merged.

## Automatic selection and resume rule

1. Reconcile live `main`, all open/draft PRs, recent merges, checks, reviews, threads, and every canonical package branch.
2. Stop with an inconsistency report if multiple packages or duplicate PRs have conflicting unfinished canonical locks.
3. Resume the single unfinished package before selecting another. `IN_PROGRESS`, `PARTIAL`, `IN_REVIEW`, and `VERIFYING` receive resume priority.
4. When no unfinished package exists, select the lowest-numbered `READY` package whose dependencies are verified complete.
5. Never select `BLOCKED` or `COMPLETE`, and never begin more than one package.

## Status evidence

- `BLOCKED`: verified external dependency prevents progress.
- `READY`: dependencies verified and no unfinished canonical lock exists.
- `IN_PROGRESS`: exact canonical branch or PR is actively claimed, resumed, or implemented.
- `PARTIAL`: useful committed and resumable work exists, but acceptance criteria remain and no external blocker exists.
- `IN_REVIEW`: all required implementation and test scope is present; review is unfinished.
- `VERIFYING`: review findings are resolved; exact-head or package-specific gates are running or being inspected.
- `COMPLETE`: normal merge and all required live `main`, release, and package-specific verification are complete. A branch-local final records commit may prepare this status prospectively, but it is not authoritative until merge verification.

## Completion count and weighted progress

- Total fixed packages: 6
- Completed: 1
- Remaining: 5
- Ready: WP-02
- Weighted progress: `20 / 100 = 20%`

These completion values are prospective until final records verification, normal merge, and live-main confirmation.

## Advancement rule

The final commit of package N prepares package N as `COMPLETE`, unlocks only the exact next package as `READY`, updates counts/progress, and records final evidence. Those values remain prospective until the exact commit is normally merged and live verification succeeds. The completing worker verifies the merge and stops; it does not begin package N+1.