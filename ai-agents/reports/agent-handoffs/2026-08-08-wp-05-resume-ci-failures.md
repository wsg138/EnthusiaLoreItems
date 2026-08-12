# WP-05 resume checkpoint — exact-head CI/tracking failures

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Draft PR: #18 — `WP-05: complete live acceptance and release LoreItems`
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`
- Exact branch head observed and resumed from: `8edf77ab8fadc488257f29b04504c88d9d1b9e77`
- Exact implementation/evidence head checkpointed by the predecessor: `05c1d59499b6785d6bf2b665f1f3cfac808de9b4`

## Reconciliation
- WP-01 through WP-04 canonical heads are contained in live `main` and are historical.
- WP-05 draft PR #18 is the only open LoreItems package PR and the sole unfinished canonical lock.
- The EnthusiaTags WP-06 canonical branch is absent; LoreItems WP-06 completion/API-blocker branches are absent.
- PR #18 has no submitted reviews, no `CHANGES_REQUESTED` review, and zero unresolved inline review threads at this checkpoint.
- WP-06 remains `BLOCKED`; it was not started.

## Completed acceptance/evidence carried forward
Permanent predecessor evidence exists for `ACC-ID-001`, `ACC-ID-002`, `ACC-CORE-001..005`; successful predecessor runs also map to `ACC-EDIT-001..002`, `ACC-DEST-001`, and `ACC-API-001`. Those older results remain traceability only after later commits until exact-head reruns pass.

One confirmed production defect remains fixed/regression-verified: already-prefixed real Floodgate recipient names were rejected by recipient binding (`e00035d937d8a7d51eb00484689c74dd1d6d394a`, cleanup `ed52a32688329be931bc6fdfc5008b393a0f2ffb`).

## Exact-head verification inspected
Exact PR head `8edf77ab8fadc488257f29b04504c88d9d1b9e77` produced:
- CI run `31252432907`: `completed/failure` in `Verify`.
- Tracking Contract run `31252432924`: `completed/failure`.
- Public API run `31252432911`: `completed/success`.
- ACC-CORE-005 Full Inventory run `31252432898`: `completed/success`.
- Exact Removal run `31252432923`: `completed/success`.
- Editor Contract run `31252432903`: `completed/success`.
- Java Identity/Core run `31252432908`: `completed/success`.
- Floodgate Identity run `31252432899`: `completed/success`.

CI annotations identify only the generic failing `Verify` step, so the concrete failing test/output still requires diagnosis from repository evidence/artifacts. No local test result is claimed because this worker environment cannot resolve GitHub from the container checkout path.

## Remaining acceptance criteria
At minimum `ACC-ENV-001`, `ACC-EDIT-003`, `ACC-PROT-001..002`, `ACC-ANOM-001..002`, `ACC-DEST-002..004`, `ACC-DIST-001..005`, `ACC-LIFE-001..002`, and `ACC-OPS-001..005` remain incomplete on the final head. Tracking is currently failing and must be repaired before `ACC-TRACK-001..003` can receive exact-head credit. All 35 cases must ultimately PASS again on the exact final JAR after the last code change.

Package-level release gates also remain: full WP-04 verification on final head, final `1.0.0` artifacts/versioning, RC-to-final upgrade, backup/restore/rollback rehearsal, independent code review, separate evidence audit, owner/operator sign-off, normal merge, post-merge `main` verification, and verified `v1.0.0` release/assets.

## Known findings
- Exact-head CI is failing.
- Exact-head Tracking Contract acceptance is failing.
- Six other current-head acceptance workflows listed above pass.
- No new production LoreItems defect is yet confirmed from these two failures.

## Blocker
None. Failing CI/acceptance is ordinary WP-05 work, not an external blocker.

## Exact next action
Diagnose CI run `31252432907` and Tracking run `31252432924` from GitHub-backed source/artifact evidence. Fix any confirmed test/workflow/production defect on this same branch, add/retain regression coverage without weakening assertions, rerun exact-head gates, then proceed to the next consolidated anomaly/destructive/lifecycle acceptance block. Do not begin WP-06.
