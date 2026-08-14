# Fixed remaining-work queue

## Queue invariants
Exactly six immutable packages. Live GitHub outranks snapshots. Resume the single unfinished canonical lock before new work. Never split packages or invent a seventh package.

| Order | Package | Weight | Status | Dependency / routing |
|---:|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | 20% | COMPLETE | normally merged and verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | 20% | COMPLETE | normally merged and verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | 20% | COMPLETE | normally merged and verified |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | 15% | COMPLETE | normally merged and verified; RC prerelease verified |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | 15% | COMPLETE | LoreItems `ed91b1d4...` live; production `v1.0.0` verified |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | 10% | COMPLETE* | Tags integration merged/verified; LoreItems PR #27 is the final state publication and becomes authoritative after normal merge/live verification |

## Progress semantics
- State prepared for publication by PR #27: 6/6 complete, 0 remaining, 100% weighted progress.
- Until PR #27 normally merges and the resulting LoreItems live `main` is verified, authoritative live-main progress remains 5/6 complete and 90%.
- `COMPLETE*` is the prospective WP-06 state being published, not a false claim that the open finalization PR is already authoritative.
- Next package: none. No seventh package exists.

## WP-06 exact integration completion evidence
- Released LoreItems dependency: `main` `ed91b1d46751544ed86fa7fa7de43cc769fc68a6`, production `v1.0.0`, JAR SHA-256 `7c862b0ae545d710a33267ad6e19a4ae26d97323e97f40707c1475c9f9ba7063`.
- Tags PR #15 reviewed head: `ad54248ead88a331119b129cfdbf55add8c78aa5`.
- Tags PR Build `31840114667`: success; Codacy `94895022587`: success, zero annotations; artifact `9233939403`: `sha256:177383a7ae24c2bafbee92df9fb015e473bbb9571445950fc60c5ab585fd897a`.
- Fresh independent CodeRabbit review of `ad54248...`: no remaining actionable WP-06 findings; all visible threads resolved and no `CHANGES_REQUESTED` before merge.
- Tags normal merge/live `main`: `14c59e925bb9e81f1f6c13ab900c81d22e0eee26`, parents `36bd6c51b7db6a94c866e5ce938b08e696050235` and `ad54248ead88a331119b129cfdbf55add8c78aa5`.
- Post-merge Build `31840466712`: success; post-merge rolling publication `31840466725`: success.
- Rolling `latest` `EnthusiaTags.jar` SHA-256: `7163a5cabd68bccbfd78283a98eef8c0be45a2bfb3313547f126b17f9d887807`.

## LoreItems finalization publication
- Canonical branch: `docs/wp-06-complete`.
- Canonical PR: #27 — `WP-06: record final remaining-work completion`.
- LoreItems base/live `main` before finalization: `ed91b1d46751544ed86fa7fa7de43cc769fc68a6`.
- Immediately preceding exact evidence head: `75aea91cfe9094fd41b35c945e926838c7483328`.
- The committed checkpoint records the immediately preceding evidence SHA by universal-rule design; PR #27 records the pushed metadata-checkpoint SHA after publication, avoiding a self-referential SHA loop.
- Authorized changed files remain only:
  - `ai-agents/WORKSPACE-STATE.md`
  - `ai-agents/WORK-QUEUE.md`
  - `ai-agents/reports/agent-handoffs/latest.md`

## Finalization evidence through predecessor `75aea91c...`
- CI `31842596691` / verify `94902478677`: `completed/success`; repository-native verification, exact-head Codacy verifier, deterministic profile, reproducible build, evidence upload, and Sentinel artifact publication passed.
- External Codacy `94902568321`: `completed/success`, zero annotations.
- Exact `enthusialoreitems-plugin` artifact `9234801123`: `sha256:9ed89317f687d89575736070505fca746b22572cbdfae566cea532215dd6db8d`.
- Verification artifact `9234800630`: `sha256:1d503eedfc8001846eb945060b0828a7be0cacc1c8dec2ff11b581d22d11f8be`.
- Automatic reviewable-transition startup `94902507564` failed before exact-SHA artifact availability with `ARTIFACT_ACQUISITION_FAILED`; it remains failed-attempt history and is not called PASS.
- Explicit startup after artifact publication: command `5298366624`, Sentinel job `167`, check `94903113658`, `completed/success`, `PAPER_SMOKE_OK`.
- Earlier supplemental restart jobs `164` / `165` on `a6d9606a...` failed the immutable cycle-2 80 C thermal gate at `83.3 C` / `83.8 C`. They are environmental failures, not PASS evidence; no additional retry was issued because live policy prohibits hammering the unchanged unsafe condition.
- WP-06 contract item 11 requires LoreItems exact-head checks for finalization and does not require that supplemental restart profile.

## Review reconciliation through predecessor `75aea91c...`
- The two earlier inline CodeRabbit threads were replied to, resolved, and outdated.
- Fresh review comment `5298410544` independently verified the head/base/scope, exact current CI/Codacy/artifact/startup evidence, no seventh package, no unintended runtime/code/workflow changes, and checkpoint-SHA semantics.
- Its sole actionable finding was stale durable-state text that omitted the completed `75aea91c...` evidence and still described already-completed gates/thread resolution as pending.
- This checkpoint records that evidence and removes those stale pending-state claims. Fresh independent review of the pushed checkpoint must confirm the finding is resolved before merge.

## Blocker
None currently verified for the contract-required finalization path. The Pi thermal gate blocks only further supplemental restart probing while unsafe.

## Merge condition and stop rule
For the pushed checkpoint recorded by PR #27, merge only if live evidence shows: applicable exact-head LoreItems CI/Codacy successful; required exact-head Sentinel startup successful; fresh independent review with zero actionable findings; no `CHANGES_REQUESTED`; zero unresolved review threads; unchanged expected head/base; and normal merge-commit availability. If all conditions hold, normally merge PR #27 with expected-head protection, verify the resulting merge is live LoreItems `main` and applicable post-merge checks succeed, then stop. If any condition fails, preserve the blocker/evidence and stop according to repository rules. Do not create or begin another package.
