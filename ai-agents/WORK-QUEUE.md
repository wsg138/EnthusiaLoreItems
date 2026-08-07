# Fixed remaining-work queue

## Queue invariants

This queue contains exactly six fixed work packages. Package identity, order, dependencies, weight, scope, acceptance criteria, branch name, and PR title are immutable. Workers may update status, evidence, counts, and progress under the committed rules but may not split, merge, rename, reorder, or redefine a package.

The COMPLETE and READY states below are the prospective publication state carried by PR #13. They become authoritative only after the commit containing this snapshot is normally merged and live `main` is verified.

When sources conflict, resolve them in this order: live GitHub state; the selected package contract; workflow documents; requirements; architecture; implementation plan; then state or handoff records.

## Ordered queue

| Order | Package | Fixed objective | Weight | Status | Exact dependency |
|---:|---|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | Complete the editor and template-management interface. | 20% | COMPLETE | PR #11 normally merged and live `main` verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | Complete destructive administration and queued-operation controls. | 20% | COMPLETE | Prospective in PR #13; normal merge and live-main verification remain |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | Complete one-use mass distributions. | 20% | READY | WP-02 COMPLETE; do not claim or begin WP-03 from PR #13 |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | Complete automated production hardening and produce a release candidate. | 15% | BLOCKED | WP-03 COMPLETE |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | Process manual live-server acceptance evidence, fix every confirmed defect, and release EnthusiaLoreItems. | 15% | BLOCKED | WP-04 release candidate published |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | Complete the separate EnthusiaTags service-API integration after LoreItems is released. | 10% | BLOCKED | WP-05 production release published |

## WP-02 completion checkpoint

- Package: WP-02 — destructive administration
- Branch: `agent/wp-02-destructive-administration`
- Ready pull request: #13, `WP-02: complete destructive administration`
- Starting live `main`: `50ac248b1583739c57b7dcb25b4e949436b736ce`
- Verified implementation head: `98d58bd76c69939159a351bd3407c108a3015227`
- GitHub Actions run `31137614006`: success on that exact implementation head
- Workflow coverage: Java 21, Gradle 8.14.3, `gradle --no-daemon clean check`, repository-tool tests, new-code complexity verification, and exact-head Codacy verification all passed
- External Codacy check `92740655340`: success with zero annotations on that exact implementation head
- CodeRabbit status: completed successfully; no `CHANGES_REQUESTED` review
- Review remediation: seven earlier implementation/coordination threads resolved; the final incremental review's two coordination findings are addressed by this publication sequence
- Outage handling: GitHub Actions was not waived; recovered GitHub-hosted runners executed the required workflow
- Status: `COMPLETE` prospectively; authoritative only after normal merge and live-main verification
- Remaining finalization: verify the final records-only branch head, resolve the two addressed coordination threads, update the PR body, normally merge PR #13, verify live `main` and post-merge checks, then stop without beginning WP-03

## Automatic selection and resume rule

1. Reconcile live `main`, all open/draft PRs, recent merges, checks, reviews, threads, and every canonical package branch.
2. Apply the authority order above before trusting a prospective queue or handoff state.
3. Stop with an inconsistency report if multiple packages or duplicate PRs have conflicting unfinished canonical locks.
4. Resume the single unfinished package before selecting another. `IN_PROGRESS`, `PARTIAL`, `IN_REVIEW`, and `VERIFYING` receive resume priority.
5. When no unfinished package exists, select the lowest-numbered `READY` package whose dependencies are verified complete.
6. Never select `BLOCKED` or `COMPLETE`, never begin more than one package, and do not claim WP-03 while completing WP-02.
7. The worker completing WP-02 must stop after merge verification.

## Completion count and weighted progress

- Total fixed packages: 6
- Completed: 2
- Remaining: 4
- Next ready package after authoritative WP-02 completion: WP-03
- Weighted progress: `40 / 100 = 40%`

These counts and weighted progress are prospective while PR #13 remains open.