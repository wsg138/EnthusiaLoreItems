# Latest agent handoff

## Current package state
- WP-04 — automated production hardening and release candidate: `COMPLETE`.
- WP-05 — live acceptance and production release: `READY` after this completion-state transition is merged.
- WP-06 — EnthusiaTags integration: `BLOCKED` on WP-05 production release.
- Final WP-04 handoff: `ai-agents/reports/agent-handoffs/2026-08-07-wp-04-complete.md`.

## Authoritative WP-04 facts
- PR #15 normally merged as `89399db2d92fd7197479a8803e920c02f5bec490`.
- Final PR #15 exact-head verification run `31204122398` passed full Gradle/repository/complexity/Codacy/profile/package/reproducibility gates; CodeRabbit was successful and no review threads remained.
- Post-merge `main` CI run `31204427939` passed on `89399db2d92fd7197479a8803e920c02f5bec490`.
- Release-recovery PR #16 normally merged as `e4b7968adea1357e7307815a5a5ef7f456f16ad1` after exact-head run `31204825737` passed, including Codacy and reproducibility.
- Post-recovery `main` CI run `31205231097` passed.
- Release workflow run `31205326905` passed.
- `v1.0.0-rc.1` is published as a GitHub prerelease targeting the original verified implementation merge `89399db2d92fd7197479a8803e920c02f5bec490`.
- Release assets verified: shaded JAR, SHA-256, CycloneDX JSON SBOM, dependency manifest, normalized-entry manifest, fixed-profile JSON, and raw test-report archive.
- Released JAR SHA-256: `3c7b6aa74ee63a4e049c5e09f2bebffe78bf50ea88caaaa3d03b55e941f427c8`.

## Remaining boundary
No live Paper/Leaf acceptance is claimed by WP-04. WP-05 owns the manual/live acceptance and production-release decision.

## Exact next action
Start WP-05 from live `main` after the completion-state PR is merged. Use the exact released `v1.0.0-rc.1` JAR and execute `docs/wp-05-manual-acceptance-matrix.md`. Do not start WP-06.
