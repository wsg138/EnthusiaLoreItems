# WP-05 authenticated-session blocker checkpoint

## Package
- Package: **WP-05 — live acceptance and production release**.
- Canonical branch: `agent/wp-05-live-acceptance-release`.
- Draft PR: #18, `WP-05: complete live acceptance and release LoreItems`.
- Status at this checkpoint: **`BLOCKED`**.
- Checkpointed implementation/evidence head: `5d64fba0451c0dd99f51e76d6f392b74109ba370`.
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`.
- Published RC under original acceptance: `v1.0.0-rc.1`, JAR SHA-256 `3c7b6aa74ee63a4e049c5e09f2bebffe78bf50ea88caaaa3d03b55e941f427c8`.

## Why WP-05 is BLOCKED
The remaining WP-05 contract requires real authenticated player sessions and physical player behavior. The available worker environment can create disposable Java 21 Paper 1.21.11 servers with Geyser/Floodgate, but it cannot provide or control the required authenticated Java/Microsoft and Bedrock/Xbox accounts.

The dependency was re-checked rather than assumed:
- repository/connectors expose no usable authenticated Java/Microsoft or Bedrock/Xbox acceptance credentials;
- the GitHub integration cannot enumerate repository secret values and no repository workflow references a usable acceptance-account secret path;
- there is no connected service that can establish an authenticated Minecraft/Xbox player session for the worker;
- offline-mode Mineflayer/Bedrock protocol clients can exercise real network/gameplay paths diagnostically, but the manual matrix explicitly requires real identity/account evidence and does not permit those clients to be credited as PASS substitutes.

Because `docs/wp-05-manual-acceptance-matrix.md` requires the complete final matrix to PASS and the WP-05 contract forbids waived, blocked, or unexecuted cases from release approval, the package cannot legitimately be completed or merged yet.

## Completed acceptance evidence
### ACC-ENV-001 — PASS
- Exact RC.
- GitHub Actions run `31217633117`.
- Permanent evidence commit `be8a3a4832dc6a78e918b39963a946731c22f624`.
- Evidence path: `docs/wp-05-acceptance/ACC-ENV-001/`.

### ACC-OPS-001 — PASS
- Exact RC.
- Corrected GitHub Actions run `31218811889`.
- Permanent evidence commit `7a1a2a63a50cfe16905955e59d8f7fdcce035a59`.
- Evidence path: `docs/wp-05-acceptance/ACC-OPS-001/`.
- Earlier run `31218454541` was a harness defect, not a LoreItems defect: it incorrectly rejected the intentional durable `UNKNOWN_DEFINITION` idempotency record.

These two RC passes remain useful historical evidence, but they do **not** approve the final release because a later RC defect was found and fixed. The complete matrix must be repeated against the exact final fixed JAR.

## Confirmed implementation defect found and fixed
### Floodgate already-prefixed recipient binding
- Real Geyser/Floodgate joined Bukkit as `*Wp05BedrockBot`.
- Published RC rejected that valid visible name while binding unresolved distribution recipients because the adapter assumed `Player#getName()` was unprefixed and attempted to add `*` itself.
- Root fix: `e00035d937d8a7d51eb00484689c74dd1d6d394a`.
- Static-analysis cleanup: `ed52a32688329be931bc6fdfc5008b393a0f2ffb`.
- Fixed-head live regression readiness correction: `9928e1f6f818c9c42fdbaa9778b475a0280d0f18`.
- Final live Geyser/Floodgate regression: run `31222017554`, PASS.
- Artifact `9010781725`, digest `sha256:5dfbc2d9ed0565e21fa2b16943331a2b0324eefdbc6ff6c85bc6daabe1dcc301`.
- Permanent report: `docs/wp-05-defects/floodgate-prefixed-recipient-binding.md`.
- Exact-head CI/Codacy around the fix passed full Gradle verification, repository tooling, complexity, exact-head Codacy, deterministic profile, package validation, and reproducibility.

## Diagnostic-only evidence
These runs intentionally receive **no acceptance credit** because they used unauthenticated/offline protocol clients. They are preserved only as defect-finding evidence.

### Java create/adopt/give/restart diagnostic
- Real Java protocol client on 1.21.11 using offline authentication.
- Exercised create, adopt, online give, offline queued give, restart delivery, and second restart replay behavior.
- Corrected run passed with one definition, three active instances, two completed deliveries, and no review-required records or duplicate replay.
- Report is under `docs/wp-05-diagnostics/`.

### Full-inventory delivery diagnostic
- Fixed-head run `31222689118` passed.
- 36 occupied storage slots kept delivery pending.
- No overflow item entity was created.
- Opening one storage slot and rejoining produced exactly one delivery.
- Another restart preserved one instance/one completed delivery with no duplicate or review-required state.
- Permanent report is under `docs/wp-05-diagnostics/` and was checkpointed in commit `02c78f6536678cea06f0dfc9a4692320726b14d9`.

### Protection/void exploration — INCONCLUSIVE, no finding
- Runs `31223151624` and `31223376860` were diagnostic harness exploration only.
- The tagged tracked item survived real fire/lava portions.
- The synthetic `/damage ... minecraft:explosion` shortcut produced ambiguous entity behavior and is not the manual matrix's required physical TNT/cactus scenario.
- No LoreItems implementation defect is claimed from these runs.
- No acceptance credit is claimed.
- The temporary automatic protection diagnostic workflow is removed by this blocker checkpoint so the PR is not left with a known-inconclusive red harness.

## Remaining WP-05 acceptance criteria
All other manual cases remain uncredited, and after authenticated sessions are available the worker must still:
1. run the remaining matrix cases faithfully with the required Java, `*`-prefixed Bedrock/Floodgate, cached-offline, and never-joined identities;
2. fix and regression-test every confirmed defect in the same WP-05 branch;
3. build the exact final fixed release-candidate JAR and repeat the **complete** matrix on that exact JAR with every case PASS;
4. rerun full WP-04 automated verification, migrations, failure/recovery matrix, profile, packaging, architecture, complexity, static analysis, exact-head Codacy, and reproducibility on the final implementation head;
5. complete independent code review and separate evidence audit with zero requested changes and zero unresolved threads;
6. record owner/operator sign-off;
7. finalize version `1.0.0`, release notes, backup/upgrade/rollback material, checksum, CycloneDX SBOM, dependency manifest, RC-to-final upgrade and rollback rehearsal;
8. normally merge PR #18, verify post-merge `main`, publish `v1.0.0` from the verified merge commit, and verify all release assets/tag target.

## Package routing consequence
- WP-05 remains the single unfinished canonical package and therefore has resume priority under `ai-agents/UNIVERSAL-AGENT-PROMPT.md`.
- WP-06 **must remain `BLOCKED`**. Its contract explicitly begins only after the verified WP-05 `v1.0.0` production release.
- A future worker must not start WP-06 merely because WP-05 is externally blocked; doing so would violate both the universal dispatcher and the WP-05/WP-06 dependency contract.

## Exact resume prerequisites
Provide a faithful acceptance path with:
- one authenticated Java/Microsoft test account;
- one authenticated Bedrock/Xbox account that joins through Geyser/Floodgate and exposes the real `*`-prefixed server-visible identity;
- the cached-offline and never-joined identity conditions required by the matrix;
- access to the designated/disposable acceptance server through the worker-controlled harness or an equivalent GitHub-backed environment.

Do not commit credentials to the repository. The future worker should use an approved secret/session mechanism and record only redacted account names/UUID evidence required by the matrix.

## Exact next action
Resume **WP-05 on PR #18**, first re-check live GitHub and this blocker dependency. If authenticated sessions are available, change WP-05 back to `IN_PROGRESS` with a new resume checkpoint and continue the manual matrix. If they are still unavailable, leave WP-05 `BLOCKED` and stop. Do **not** claim or begin WP-06.
