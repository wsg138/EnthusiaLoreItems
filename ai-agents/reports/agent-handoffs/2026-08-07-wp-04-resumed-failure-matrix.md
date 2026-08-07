# WP-04 resume checkpoint — failure-matrix implementation

## Active package
- Package: WP-04 — automated production hardening and release candidate
- Status: `IN_PROGRESS`
- Branch: `agent/wp-04-production-hardening`
- Draft PR: #15
- Starting live `main`: `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`
- Resumed implementation/evidence head: `43b4092e6dc8c873ed7c77ccee4b87e5e5a44f34`

## Completed acceptance criteria at resume
- Operator/recovery/backup/restore/rollback/incident documentation is complete.
- Executable WP-05 manual acceptance matrix is complete.
- WP-03 dependency is normally merged and post-merge verified.
- Canonical-lock reconciliation confirms WP-04 is the only unfinished package.

## Remaining acceptance criteria
- Deterministic failure-injection/restart harness for every durable state machine and required failure point.
- Queue saturation/backpressure coverage for all named bounded work surfaces.
- Every historical schema migration/upgrade path with rollback/integrity/WAL/index checks.
- Reload/shutdown bounded-drain and lifecycle coverage.
- Stable versioned Bukkit API tests/docs and source/binary compatibility verification.
- Fixed-scenario performance/profile harness and committed threshold evidence.
- Static-analysis/complexity remediation without broad suppressions.
- `1.0.0-rc.1` packaging, shaded jar, CycloneDX JSON SBOM, dependency manifest, checksum, reports, release notes, reproducibility and package smoke verification.
- Full-package harsh and independent review, every confirmed fix, exact-head Actions/Codacy, zero requested changes and zero unresolved threads.
- Normal merge, live `main` verification, and verified `v1.0.0-rc.1` prerelease/assets from the merge SHA.

## Tests and exact evidence at resume
- GitHub Actions CI run `31179353021` for exact head `43b4092e6dc8c873ed7c77ccee4b87e5e5a44f34`: `completed/success`.
- `verify` job: Gradle verification, repository tooling, new-code complexity, and exact-head Codacy all `success`.
- Combined commit status: CodeRabbit `success`.
- PR reviews: none; requested changes: none; review threads: zero.
- Local checkout clone failed because the runtime cannot resolve `github.com`; no local build result is claimed.

## Findings
- No concurrent claimant or duplicate canonical PR exists.
- Historical WP-01 through WP-03 branch heads are fully contained in live `main`.
- No production-code defect is established yet in WP-04.

## Blocker
None.

## Exact next action
Implement a reusable deterministic failure-injection/restart test harness and the first complete durable state-machine group, beginning with storage-backed delivery/operation flows that expose intent, claim, apply, verification, terminal, reload, and shutdown boundaries. Commit the coherent implementation plus exact verification evidence on this same branch.
