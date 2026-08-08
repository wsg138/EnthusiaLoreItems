# WP-05 confirmed defect — Floodgate-prefixed distribution binding

## Status
FIXED and regression-verified on PR #18; final WP-05 acceptance remains incomplete.

## Severity and scope
Release-blocking functional defect for Bedrock/Floodgate distribution identity binding. It did not demonstrate item loss or duplicate creation.

Affected requirements/cases:
- Floodgate `*`-prefixed identity preservation and case-insensitive future binding.
- `ACC-ID-002`.
- `ACC-DIST-002` and downstream campaign delivery for unresolved Bedrock names.

## Discovery
The original published `v1.0.0-rc.1` JAR (`3c7b6aa74ee63a4e049c5e09f2bebffe78bf50ea88caaaa3d03b55e941f427c8`) was exercised through a real Bedrock protocol connection routed through Geyser/Floodgate. Floodgate exposed the player to Bukkit as `*Wp05BedrockBot` and LoreItems logged:

`Skipping distribution identity binding for unsupported player name '*Wp05BedrockBot': currentName must be an unprefixed non-blank player name`

Discovery run: `31220565970`; artifact `9010240807`, digest `sha256:39cd148e623743ad05f590e83fe2a49eda3038607bdc8c080f023b83bbfafb7b`.

The later client command-packet failure in that diagnostic was unrelated; the identity-binding defect occurred immediately on the successful real Floodgate join before that client-side failure.

## Root cause
`PaperDistributionRecipientBindingWorker` assumed `Player#getName()` for a Floodgate player was unprefixed. It rejected every leading `*` before applying the Floodgate flag, then added `*` itself. Real Geyser/Floodgate already supplies the configured server-visible prefix to Bukkit, so valid names were rejected before `BindDistributionRecipientsUseCase` could match unresolved campaign recipients.

Existing tests covered only an artificial unprefixed Floodgate name and therefore missed the real adapter boundary.

## Fix
Production normalization now:
- preserves an already-prefixed Floodgate name exactly;
- still supports an unprefixed Floodgate name by adding `*` once;
- rejects a bare `*`;
- rejects `*` on non-Floodgate identities.

Primary fix commit: `e00035d937d8a7d51eb00484689c74dd1d6d394a`.
Static-analysis cleanup: `ed52a32688329be931bc6fdfc5008b393a0f2ffb`.
Final live-regression readiness correction: `9928e1f6f818c9c42fdbaa9778b475a0280d0f18`.

Regression added: `floodgateServerVisiblePrefixedNameIsPreservedWithoutDoublePrefix` in `PaperDistributionRecipientBindingWorkerTest`.

## Verification
### Automated/static
The full Gradle suite passed with the new regression. On source-clean head `ed52a32688329be931bc6fdfc5008b393a0f2ffb`, CI run `31221482305` passed Gradle verification, repository tooling, complexity, exact-head Codacy, deterministic profile, RC package validation, evidence packaging, and reproducibility; external Codacy reported zero issues.

### Live adapter regression
Final fixed-head live regression run `31222017554` on head `9928e1f6f818c9c42fdbaa9778b475a0280d0f18` passed. Artifact `9010781725`, digest `sha256:5dfbc2d9ed0565e21fa2b16943331a2b0324eefdbc6ff6c85bc6daabe1dcc301`.

The built fixed jar SHA-256 was `e817b066ce18daca3556b83e20828ad40d45257f2c75e03b5ee4e43221820dd1`.

Observed sequence:
- Geyser connected `Wp05BedrockBot`.
- Floodgate exposed the server-visible identity `*Wp05BedrockBot`.
- Bukkit logged `*Wp05BedrockBot joined the game`.
- LoreItems distribution identity binding was active before the join.
- The old `Skipping distribution identity binding for unsupported player name '*Wp05BedrockBot'` warning was absent.
- Client spawned and remained connected through the regression window.

This live regression deliberately disabled Xbox validation so it is defect-specific regression evidence, not `ACC-ID-002` acceptance credit.

## Harness findings encountered while validating
- The first fixed-head workflow used `./gradlew` although this repository intentionally has no wrapper; corrected to pinned Gradle 8.14.3.
- Two later attempts raced Geyser's own `Done (...)` line before Paper/LoreItems delayed initialization; readiness was corrected to require Paper's final help line plus LoreItems' distribution-worker activation before launching the Bedrock client.
- These were harness failures. Neither reproduced the original LoreItems rejection after the source fix.

## Final review result
The production diff is limited to the adapter normalization boundary. No broad validation weakening or platform dependency was introduced. The domain/application name key contract remains unchanged. No unresolved source finding remains for this defect.

## Release consequence
`v1.0.0-rc.1` is now known defective for real Floodgate-prefixed campaign binding and cannot be promoted unchanged. The final WP-05 jar must include this fix, and the package contract requires the complete matrix to be rerun against that final exact jar after the last code change.
