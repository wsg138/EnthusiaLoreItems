# WP-05 Floodgate defect remediation checkpoint — 2026-08-07

## Package state
- Package: WP-05 — live acceptance and production release
- Status: `IN_PROGRESS`
- Branch: `agent/wp-05-live-acceptance-release`
- Draft PR: #18
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`
- Exact published RC: `v1.0.0-rc.1`, JAR SHA-256 `3c7b6aa74ee63a4e049c5e09f2bebffe78bf50ea88caaaa3d03b55e941f427c8`
- Final regression head before this checkpoint: `9928e1f6f818c9c42fdbaa9778b475a0280d0f18`

## Completed acceptance evidence
- `ACC-ENV-001`: audited PASS against the exact published RC; run `31217633117`, evidence commit `be8a3a4832dc6a78e918b39963a946731c22f624`.
- `ACC-OPS-001`: audited PASS against the exact published RC; corrected run `31218811889`, evidence commit `7a1a2a63a50cfe16905955e59d8f7fdcce035a59`.

These two historical passes remain useful evidence, but the confirmed source defect means the WP-05 final-release gate still requires the complete matrix to be repeated against the exact final fixed jar after the last code change.

## Confirmed implementation defect
Real Geyser/Floodgate join of `*Wp05BedrockBot` against `v1.0.0-rc.1` caused LoreItems to reject distribution identity binding because `PaperDistributionRecipientBindingWorker` assumed Bukkit exposed Floodgate names without the configured `*` prefix.

Discovery:
- run `31220565970`
- artifact `9010240807`
- artifact digest `sha256:39cd148e623743ad05f590e83fe2a49eda3038607bdc8c080f023b83bbfafb7b`
- affected matrix: at least `ACC-ID-002`, `ACC-DIST-002`, and dependent Bedrock campaign binding/delivery flows.

Root cause and remediation are permanently documented at `docs/wp-05-defects/floodgate-prefixed-recipient-binding.md`.

## Fix
- primary source/regression commit: `e00035d937d8a7d51eb00484689c74dd1d6d394a`
- static-analysis cleanup: `ed52a32688329be931bc6fdfc5008b393a0f2ffb`
- final live-regression readiness correction: `9928e1f6f818c9c42fdbaa9778b475a0280d0f18`

The adapter now preserves an already-prefixed Floodgate name, adds the prefix only when absent, rejects bare `*`, and still rejects prefixed non-Floodgate identities. Unit regression: `floodgateServerVisiblePrefixedNameIsPreservedWithoutDoublePrefix`.

## Verification
- Full source CI on `ed52a32688329be931bc6fdfc5008b393a0f2ffb`: run `31221482305` success including Gradle, repository tooling, complexity, exact-head Codacy, deterministic profile, RC artifact validation/evidence, and reproducibility. External Codacy: success/zero issues.
- Final defect-specific live regression on `9928e1f6f818c9c42fdbaa9778b475a0280d0f18`: run `31222017554` success.
- Fixed regression artifact `9010781725`, digest `sha256:5dfbc2d9ed0565e21fa2b16943331a2b0324eefdbc6ff6c85bc6daabe1dcc301`.
- Fixed test jar SHA-256: `e817b066ce18daca3556b83e20828ad40d45257f2c75e03b5ee4e43221820dd1`.
- Live evidence shows Geyser client login, Floodgate server-visible `*Wp05BedrockBot`, Bukkit join, and no old LoreItems rejection warning.
- The regression disables Xbox validation and therefore is defect-fix evidence only, not `ACC-ID-002` acceptance credit.

## Diagnostic evidence
A separate Mineflayer Java 1.21.11 offline-auth diagnostic completed create, adopt, online give, offline queued give, restart delivery, and replay restart with stable physical/durable counts. Corrected run `31220039263`, permanent report `docs/wp-05-diagnostics/java-protocol-core-delivery.md`. It earns no acceptance credit because identity authentication differs from the production acceptance boundary.

## Harsh review findings
- Initial WP-05 blocker state accidentally condensed prior WP-04 evidence; fixed at `ed869117dc449c0c96c824cf2668725ea711662b`.
- First `ACC-OPS-001` harness incorrectly treated the durable `UNKNOWN_DEFINITION` idempotency result as unintended work; corrected at `c00271761e60446f4611706f6b70f3d00ccfde03`.
- First Java diagnostic evidence query selected a nonexistent definition column; corrected at `186ae452bc69bd5efae1c848fe327e7d9164c418`.
- Floodgate fix validation had three harness-only failures: missing Gradle wrapper assumption, then two readiness races against Geyser's own `Done` line. They were corrected without weakening the production assertion.
- Final production patch review found no remaining implementation issue in the Floodgate normalization change.

## Remaining acceptance criteria
All matrix cases other than the two audited baseline passes remain uncredited. In addition, after the final code change the complete matrix must be rerun against the exact final jar. Required review/evidence audit, operator sign-off, version `1.0.0`, normal merge, post-merge verification, and production release remain outstanding.

## Current external boundary
No repository/connector-provided authenticated Java/Microsoft or Bedrock/Xbox acceptance credentials are available. GitHub-hosted runners can operate the Paper/Geyser/Floodgate server, and unauthenticated protocol clients are useful for defect discovery, but they cannot honestly establish production identity acceptance. Continue any faithful non-identity/live-server work that remains before declaring this the sole blocker.

## Progress
- Packages complete: 4/6.
- Packages remaining: 2/6.
- Weighted progress: 75%.
- WP-05 audited acceptance cases: 2.
- Confirmed WP-05 implementation defects: 1 found, 1 fixed/regression-verified.

## Exact next action
Continue WP-05 on this same branch/PR. Use the fixed current code for further defect discovery and executable live cases. Establish authenticated Java and Bedrock/Floodgate sessions for identity-sensitive acceptance, then run the required failed/shared-state regression subset and ultimately the complete final-jar matrix. Do not begin WP-06.
