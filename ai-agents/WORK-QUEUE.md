# Fixed remaining-work queue

## Queue invariants

This queue contains exactly six fixed work packages. Package identity, order, dependencies, weight, scope, acceptance criteria, branch name, and PR title are immutable. Live GitHub outranks this snapshot.

The COMPLETE and READY states below are the prospective publication state carried by PR #13. They become authoritative only after the exact commit containing them is normally merged and live `main` is verified.

## Ordered queue

| Order | Package | Fixed objective | Weight | Status | Exact dependency |
|---:|---|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | Complete the editor and template-management interface. | 20% | COMPLETE | PR #11 normally merged and live `main` verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | Complete destructive administration and queued-operation controls. | 20% | COMPLETE | Prospective in PR #13; exact-head final gates, normal merge, and live-main verification remain |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | Complete one-use mass distributions. | 20% | READY | WP-02 COMPLETE; do not claim or begin WP-03 from PR #13 |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | Complete automated production hardening and produce a release candidate. | 15% | BLOCKED | WP-03 COMPLETE |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | Process manual live-server acceptance evidence, fix every confirmed defect, and release EnthusiaLoreItems. | 15% | BLOCKED | WP-04 release candidate published |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | Complete the separate EnthusiaTags service-API integration after LoreItems is released. | 10% | BLOCKED | WP-05 production release published |

## WP-02 completion checkpoint

- Package: WP-02 — destructive administration
- Branch: `agent/wp-02-destructive-administration`
- Ready pull request: #13, `WP-02: complete destructive administration`
- Starting live `main`: `50ac248b1583739c57b7dcb25b4e949436b736ce`
- Resume starting head: `956f8c9a433d2819bbec16f072f7a44149fbbbad`
- Verified pre-final head: `eddded7254e78113dc29f0421dfc2becbf9194ee`
- Status: `COMPLETE` prospectively; authoritative after normal merge and live-main verification
- Completed criteria: complete destructive workflows, queue administration, focused automated coverage, operator documentation, independent harsh review, and final author-side harsh review
- GitHub Actions recovery evidence: run `31117469546`, attempt 2, completed successfully for exact head `eddded7254e78113dc29f0421dfc2becbf9194ee`
- Exact-head workflow results: Java 21 and Gradle 8.14.3 setup, `gradle --no-daemon clean check`, repository-tool tests, new-code complexity verification, and exact-head Codacy verification all passed
- Exact-head Codacy check: `92670804638`, success with zero annotations
- Review state before this coordination commit: no submitted reviews, requested changes, or unresolved review threads
- Outage handling: GitHub Actions was not waived; the recovered runner completed the exact-head workflow successfully
- Remaining finalization: run final exact-head checks for this coordination commit, reconcile any new review state, normally merge PR #13, verify the merge commit and live `main`, then stop without beginning WP-03

## Automatic selection and resume rule

1. Reconcile live `main`, all open/draft PRs, recent merges, checks, reviews, threads, and canonical package branches.
2. Resume any unfinished canonical package before selecting another.
3. When no unfinished package exists, select the lowest-numbered dependency-verified `READY` package.
4. The worker completing WP-02 must stop after merge verification and must not claim or begin WP-03.
5. Never begin more than one package.

## Completion count and weighted progress

- Total fixed packages: 6
- Completed: 2
- Remaining: 4
- Next ready package after authoritative WP-02 completion: WP-03
- Weighted progress: `40 / 100 = 40%`

These counts and weighted progress are prospective while PR #13 remains open.