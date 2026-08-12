# WP-05 resumed through GitHub-hosted acceptance harness — 2026-08-07

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Draft PR: #18
- Resume base head: `ed869117dc449c0c96c824cf2668725ea711662b`
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`
- Exact RC JAR SHA-256: `3c7b6aa74ee63a4e049c5e09f2bebffe78bf50ea88caaaa3d03b55e941f427c8`

## Reconciliation
- Live `main` remains unchanged at the recorded starting SHA.
- PR #18 remains open, draft, mergeable, and on the canonical WP-05 branch.
- No submitted reviews, requested changes, or unresolved review threads were present on resume.
- Existing exact-head CI and Codacy on `ed869117dc449c0c96c824cf2668725ea711662b` remain successful.
- The WP-05 contract still requires every live matrix case against the exact RC and does not permit blocked/not-run cases to count as PASS.

## Blocker re-evaluation
The original lack of a remote Minecraft/SSH connector remains true, but it no longer prevents all useful live execution. The exact RC was materialized from the repository's GitHub Actions evidence artifact, and GitHub-hosted runners can provide the networked disposable Java 21 environment that the local container cannot.

The package is therefore resumed as `IN_PROGRESS`. GitHub Actions will be used as a designated disposable acceptance server for cases that can be faithfully executed there. Cases requiring real Java/Bedrock player sessions remain unclaimed until equivalent live clients/accounts are established; they are not waived or simulated with unsupported shortcuts.

## First resumed section
- Add a WP-05 acceptance workflow for `ACC-ENV-001`.
- Pin Paper 1.21.11 build 116, which is Java 21 compatible.
- Download the exact published `v1.0.0-rc.1` LoreItems artifact and verify its SHA-256 before startup.
- Enable Geyser and Floodgate in the disposable environment and record their exact downloaded builds/hashes.
- Capture startup logs, Java/server/plugin versions, schema/integrity/WAL evidence, configuration, baseline durable counts, and queue/admin evidence.
- Upload raw evidence and then commit the audited case record/index back to this same branch.

## Completed acceptance criteria
None yet. This checkpoint only lifts the external-environment blocker enough to resume execution.

## Remaining acceptance criteria
The complete WP-05 contract remains.

## Exact next action
Run `ACC-ENV-001` on a GitHub-hosted disposable Paper 1.21.11 server against the exact RC, audit its evidence, and commit the case result. Do not begin WP-06.
