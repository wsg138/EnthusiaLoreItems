# Workspace state

## Snapshot warning
Live GitHub is authoritative. Resolve conflicts in this order: live GitHub; selected package contract; workflow docs; requirements; architecture; implementation plan; state/handoffs.

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `PARTIAL`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Pull request: draft PR #18, `WP-05: complete live acceptance and release LoreItems`
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`
- Exact implementation/evidence head checkpointed by the 2026-08-08 06:12 EDT worker: `05c1d59499b6785d6bf2b665f1f3cfac808de9b4`
- Session resume checkpoint: `b075b2a6fc71a28e52d21c3ab510bcbd7c33b1be`
- Current permanent handoff: `ai-agents/reports/agent-handoffs/2026-08-08-wp-05-ci-tracking-partial.md`
- Dependency satisfied by verified WP-04 RC `v1.0.0-rc.1`.
- WP-06 remains blocked until the verified WP-05 production `v1.0.0` release.

## Package registry
| Package | Weight | Status | Reason |
|---|---:|---|---|
| WP-01 | 20% | COMPLETE | normally merged and verified |
| WP-02 | 20% | COMPLETE | normally merged and verified |
| WP-03 | 20% | COMPLETE | normally merged and verified |
| WP-04 | 15% | COMPLETE | normally merged; RC prerelease verified |
| WP-05 | 15% | PARTIAL | canonical draft PR #18 is active; useful acceptance/fix work is committed but matrix/release gates remain |
| WP-06 | 10% | BLOCKED | requires verified WP-05 production release |

- Packages complete: 4/6.
- Weighted completed progress: 75%.
- No package weight is awarded to WP-05 until complete.

## Owner-approved WP-05 scope amendment
Real Microsoft/Xbox account authentication is out of scope. WP-05 must not request credentials/device-code sign-ins or claim authenticated Microsoft/Xbox coverage. Identity-sensitive acceptance remains required at the server-visible Java/Floodgate boundary, including real `*`-prefixed Floodgate names, UUID/name behavior, cached-offline/never-joined resolution, commands/GUI, delivery, audit, API and distribution behavior.

## Confirmed production finding
One LoreItems production defect has been confirmed in WP-05 and is fixed/regression-verified: real Geyser/Floodgate exposed a valid already-prefixed Bedrock name and the recipient-binding worker rejected it. Production fix: `e00035d937d8a7d51eb00484689c74dd1d6d394a`; static cleanup: `ed52a32688329be931bc6fdfc5008b393a0f2ffb`; permanent report: `docs/wp-05-defects/floodgate-prefixed-recipient-binding.md`.

No additional production LoreItems defect was confirmed by the 06:02–06:12 EDT worker.

## Current worker fixes
- `6aa457e499341ad405438961bf4999d74c515627` — fixed a test-only CI compile mismatch in `PaperSharedContainerRestrictionTest` by using the real `VoidLossUseCase.PrepareResult.of(...)` factory.
- `05c1d59499b6785d6bf2b665f1f3cfac808de9b4` — fixed tracking acceptance setup to clear the create-from-held source before giving the tracked instance, and made fatal Mineflayer failures terminate instead of hanging cleanup. No tracking assertion was weakened.

## Acceptance evidence
Permanent predecessor PASS evidence exists for these seven cases on coherent earlier heads:
- `ACC-ID-001`
- `ACC-ID-002`
- `ACC-CORE-001..005`

Successful predecessor workflows additionally provide named traceability for:
- `ACC-EDIT-001..002` — run `31249416167`.
- `ACC-DEST-001` — run `31249416166`.
- `ACC-API-001` — run `31249416184`.

Historical `ACC-ENV-001` and `ACC-OPS-001` are traceability only.

Because any later commit invalidates exact-head evidence, the predecessor runs above are not claimed as exact-head PASS for `05c1d59499b6785d6bf2b665f1f3cfac808de9b4`. At the second and final workflow status inspection for that head, all applicable jobs were still running and none had reported failure:

| Workflow | Run | Last observed state |
|---|---:|---|
| CI | `31252181136` | `in_progress` |
| WP-05 Java Identity and Core Acceptance | `31252181145` | `in_progress` |
| WP-05 ACC-CORE-005 Full Inventory Acceptance | `31252181129` | `in_progress` |
| WP-05 Floodgate Identity Acceptance | `31252181148` | `in_progress` |
| WP-05 Editor Contract Acceptance | `31252181135` | `in_progress` |
| WP-05 Exact Removal Acceptance | `31252181128` | `in_progress` |
| WP-05 Public API Acceptance | `31252181134` | `in_progress` |
| WP-05 Tracking Contract Acceptance | `31252181180` | `in_progress` |

No PASS is inferred from those running states. The worker stopped polling after two status cycles.

## Verification findings from this worker
- Predecessor CI run `31249416193` failed because the shared-container unit test referenced a nonexistent `PrepareResult.unavailable(String)` helper; fixed as above.
- Predecessor Tracking Contract run `31249416180` was cancelled after the bot first recorded a concrete harness failure: two inventory items were present because the template source was intentionally retained by create-from-held. Its fatal path also stayed connected and could hang cleanup; both workflow defects were fixed as above.
- Predecessor Editor Contract run `31249416167` was `completed/success` and explicitly executed `ACC-EDIT-001` + `ACC-EDIT-002`.
- Predecessor Exact Removal run `31249416166` was `completed/success` and explicitly executed `ACC-DEST-001`.
- Predecessor Public API run `31249416184` was `completed/success` and explicitly executed `ACC-API-001`.
- Local executable verification is unavailable in the current worker environment (`gh` absent and GitHub DNS unavailable); no local test result is claimed.
- PR #18 had zero unresolved inline review threads when inspected during this worker session.

## Remaining acceptance criteria
The complete 35-case matrix must ultimately PASS against the exact final JAR after the last code change. Uncovered/incomplete areas include at least:
- `ACC-ENV-001`
- `ACC-EDIT-003`
- `ACC-PROT-001..002`
- `ACC-ANOM-001..002`
- `ACC-DEST-002..004`
- `ACC-DIST-001..005`
- `ACC-LIFE-001..002`
- `ACC-OPS-001..005`

Existing workflows for `ACC-ID-001..002`, `ACC-CORE-001..005`, `ACC-EDIT-001..002`, `ACC-TRACK-001..003`, `ACC-DEST-001`, and `ACC-API-001` must first complete successfully on the current head before current-head credit can be recorded, and all cases must be repeated once more after the final code change.

Package-level gates still required:
- every future confirmed implementation defect fixed and regression-tested in WP-05;
- complete final 35-case exact-JAR matrix;
- complete WP-04 automated migration/failure/saturation/profile/package/reproducibility/static-analysis/Codacy verification on final head;
- version `1.0.0`, release notes, SHA-256, CycloneDX SBOM, Gradle dependency manifest and acceptance index;
- RC-to-final upgrade, backup/restore and rollback rehearsal;
- independent harsh code review and separate evidence audit, no requested changes, zero unresolved threads;
- owner/operator sign-off;
- normal merge commit for PR #18, post-merge live `main` verification, and verified production `v1.0.0` tag/release/assets.

## Blocker
None verified. WP-05 is resumable and remains the sole active/unfinished canonical package. WP-06 remains `BLOCKED`.

## Exact next action
Resume PR #18 and inspect the already-running exact-head workflows for `05c1d59499b6785d6bf2b665f1f3cfac808de9b4`. Do not rerun successful work unnecessarily. Repair any confirmed exact-head code/test/workflow failure on this same branch. If current workflows pass, record exact-head credit for the named cases, then implement the next consolidated disposable-Paper acceptance block for `ACC-ANOM-001..002`, `ACC-DEST-002..004`, and `ACC-LIFE-001..002`. Continue with `ACC-EDIT-003`, protection, distribution, environment, backup/recovery and load cases, then the final full matrix/release gates. Do not begin WP-06.
