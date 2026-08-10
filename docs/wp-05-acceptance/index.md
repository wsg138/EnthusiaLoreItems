# WP-05 acceptance evidence index

WP-05 uses this committed ledger for audited acceptance results. A successful workflow by itself is not a PASS: each case below was reconciled against the case contract, exact-head workflow result, structured evidence bundle, and release-candidate identity before being credited.

## Final audited candidate

- Package: **WP-05 — live acceptance and production release**
- Tested source head: `b0fee367a900e28adca8ce48789353aebb1a4f52`
- Production version: **1.0.0**
- Paper: **1.21.11 build 116**
- Java: **21**
- Exact LoreItems JAR SHA-256: `e4c9390c846e32d3c1e46dfb4216315a7294172804507e3f04699bdacda07854`
- CI run: `31431568885`
- CI verification artifact: `wp04-verification-b0fee367a900e28adca8ce48789353aebb1a4f52` / artifact `9079382608` / digest `sha256:25d805e6a29d5b62102f7d4dce841f106afda1a66bd838b0f22d774eecf146ad`
- Production JAR artifact: `enthusialoreitems-plugin` / artifact `9079383783` / digest `sha256:0dcc17851cd2317fd6581a0a2568ac1db3db848034182b974475a0de9aabc8e0`
- The two final Tracking fixes after the 1.0.0 transition changed only the acceptance workflow/test harness instrumentation. CI rebuilt the same production JAR SHA-256 above.

## Canonical case ledger

| Case | Result | GitHub run | Evidence artifact | Audit finding |
|---|---|---:|---|---|
| ACC-ENV-001 | PASS | `31431568946` | `9079362021` / `sha256:33681e38007fe383d1c07e749affa172aeb0b7dbc28b32dab10dca854922b0c2` | Required environment starts correctly and degraded optional integrations remain non-fatal. |
| ACC-ID-001 | PASS | `31431568932` | `9079375614` / `sha256:20a4b559db9f67b2195653be445dee8168bc622235ec54af7c319e86caf896bd` | Java online/offline identity and target resolution contract passed. |
| ACC-ID-002 | PASS | `31431568861` | `9079433793` / `sha256:dba425d437c7a83c9a7037105f8e4b0df66b74162f516f43bff36b031e4866b3` | Floodgate/server-visible identity, literal `*` names, UUID/name paths, cached-offline and never-joined behavior passed. |
| ACC-CORE-001 | PASS | `31431568932` | `9079375614` | Definition creation and durable identity passed. |
| ACC-CORE-002 | PASS | `31431568932` | `9079375614` | Direct delivery and unique-instance persistence passed. |
| ACC-CORE-003 | PASS | `31431568932` | `9079375614` | Restart/replay preserved canonical state without duplicate application. |
| ACC-CORE-004 | PASS | `31431568932` | `9079375614` | Audit/database integrity and physical-state reconciliation passed. |
| ACC-CORE-005 | PASS | `31431568984` | `9079393089` / `sha256:13cda6153afe302479cc8364f7a8486c66ce619bd16183c417af840edf0bf953` | Full inventory queues delivery and later completes exactly once when space becomes available. |
| ACC-EDIT-001 | PASS | `31431568996` | `9079387424` / `sha256:22f1345609152c03d60d9d4a80accb7a67cb8b7e9a10dbcb84fa285c6cf1c795` | Editor creates an immutable next revision through the supported UI path. |
| ACC-EDIT-002 | PASS | `31431568996` | `9079387424` | Editor confirmation/cancel and revision integrity contract passed. |
| ACC-EDIT-003 | PASS | `31431568858` | `9079415995` / `sha256:f4d3f4cf0b6c34fb5eb77459f9060d2455efd644ec648808c17fa097e3435350` | Revision rollout converged across player, container, nested, display, dropped, offline, unloaded and restart paths with stable instance IDs and replay safety. |
| ACC-TRACK-001 | PASS | `31431568877` | `9079434328` / `sha256:f0da015e709e7111a56ac445cc0e06f62eec4bb265980387ef6c46c6911c2b74` | Player inventory/offhand/armor/cursor/Ender/offline/rejoin continuity passed. |
| ACC-TRACK-002 | PASS | `31431568877` | `9079434328` | Chest/hopper/nested shulker+bundle tracking, allowed/restricted policy, unload/reload retention and authoritative close reconciliation passed. |
| ACC-TRACK-003 | PASS | `31431568877` | `9079434328` | Natural drop/pickup, item frame, glow item frame, armor stand, death and chunk lifecycle passed. |
| ACC-PROT-001 | PASS | `31431568859` | `9079382465` / `sha256:8f1e7f4f58ffbb75f4c2a1584de05b57fb76d6ac78286eba8c0d09bcb294b999` | Protected tracked-item movement/use restrictions passed. |
| ACC-PROT-002 | PASS | `31431568958` | `9079370487` / `sha256:8ac5a081e38012009e4c1377143cd4e77e0e6d49d1ba92d9ae1fe591cd96402b` | Conversion/transform protection preserves lore-item identity and blocks unsafe conversion paths. |
| ACC-ANOM-001 | PASS | `31431568940` | `9079575361` / `sha256:39e92a96f3224ec2c0752796b8363b908f4623cefa4945db42fb732ec60f6105` | Duplicate/anomaly warning and inspection contract passed through its full timing window. |
| ACC-ANOM-002 | PASS | `31431568860` | `9079384967` / `sha256:ba723c2213b371995d33345f9b193bb6607637fc2aacec44182ece8ef76363ea` | Ambiguous mutation state is quarantined/recovered without unsafe automatic mutation. |
| ACC-DEST-001 | PASS | `31431568902` | `9079406367` / `sha256:b847f74879793c143299dbf8897078144dd036b7e36e050ee2a13d9e2ff97b07` | Exact instance removal targets only the requested instance. |
| ACC-DEST-002 | PASS | `31431568868` | `9079385089` / `sha256:1593825d889d75b31b599f2191dcbdb3f2efef900ddaba454ec10784c0ec663f` | Destructive lifecycle/restart behavior passed without unsupported global Bukkit `/reload`. |
| ACC-DEST-003 | PASS | `31431568921` | `9079386682` / `sha256:c82db49995764b205d8d3f48993526c592c8b88712458cbd0414a5f6adeb7bca` | Full delete plus late physical-copy/tombstone handling passed. |
| ACC-DEST-004 | PASS | `31431568868` | `9079385089` | Destructive replay/restart remains idempotent and durable. |
| ACC-DIST-001 | PASS | `31431568953` | `9079425853` / `sha256:3dfb2c549b7bfcce10de63545e491b49cb546a94253b241222576f68db7c011e` | Campaign creation and deterministic recipient targeting passed. |
| ACC-DIST-002 | PASS | `31431568953`, `31431568912` | `9079425853`, `9079506759` / Floodgate digest `sha256:9d9be1bdac552d351607733ef1981522e96b29c3226fe71a1843a238a67d3623` | Java and Floodgate recipients resolve and receive exactly once across online/offline paths. |
| ACC-DIST-003 | PASS | `31431568953` | `9079425853` | Resume/restart campaign state does not duplicate completed delivery. |
| ACC-DIST-004 | PASS | `31431568953` | `9079425853` | Partial/full-inventory campaign work remains queued and later converges safely. |
| ACC-DIST-005 | PASS | `31431568953`, `31431568912` | `9079425853`, `9079506759` | Campaign audit/status and mixed Java/Floodgate lifecycle remain durable and idempotent. |
| ACC-API-001 | PASS | `31431568874` | `9079422435` / `sha256:9b1a3be764ce4540f8fa62f63c33b0acb1b50573839dae523fb4ad6622724c7b` | Public API delivery/idempotency and failure contract passed. |
| ACC-LIFE-001 | PASS | `31431568871`, `31431568870` | `9079391644`, `9079460785` / mixed digest `sha256:32bbeefb99ca4ecd2a31818fce7c96bffd1edd3ad0872bce91f95dc0cf872f86` | Supported atomic `/loreitems reload` applies valid configuration, rejects invalid configuration while retaining last-known-good behavior, and does not lose queued work. |
| ACC-LIFE-002 | PASS | `31431568868`, `31431568870` | `9079385089`, `9079460785` | Clean shutdown/restart with mixed pending work drains/replays safely and preserves integrity. |
| ACC-OPS-001 | PASS | `31431568946` | `9079362021` | Startup/degraded optional integration behavior passed. |
| ACC-OPS-002 | PASS | `31431568894` | `9079373837` / `sha256:05e274a96fe7a8ff8538a5eab10f7a87e0adc4c4c50c6affc4ef303b1a5b41f4` | Offline backup produces a consistent recoverable database snapshot. |
| ACC-OPS-003 | PASS | `31431568894` | `9079359165` / `sha256:a6335c65276a48fbdf3698005037392dd9f97684396631d4029a074c034d3e56` | Release rollback procedure restores a valid prior state without corrupting data. |
| ACC-OPS-004 | PASS | `31431568863` | `9079368303` / `sha256:f3d79a7284cb08ec52f0cd6f8f805480f39a1b000d212adbc2dfd5a393823284` | Sustained load remains bounded and completes accepted work without silent loss. |
| ACC-OPS-005 | PASS | `31431568863` | `9079368303` | Queue saturation/backpressure is explicit, bounded and recoverable. |

## Additional review evidence

- Mutation review contract run `31431568897`, artifact `9079342198`, digest `sha256:4f59c2f8a181ff6c6f53c93e0c138a7b7cf0e0e0a3e0c920ccd890e04df6dc`, passed on the tested head.
- GitHub Actions run pages and this committed ledger are permanent repository records. Raw uploaded artifacts have their configured retention windows; the case adjudications, run IDs, artifact IDs and content digests above remain committed here after raw archive expiry.
- Earlier failed Tracking attempts are intentionally not credited. They exposed two deterministic fixture defects: a synthetic nested-placement transition that raced periodic reconciliation and an unsafe high chunk-loader teleport. Both were fixed only in acceptance-workflow/test-harness instrumentation; the final credited Tracking run is `31431568877`.

## Release gate adjudication

- Evidence audit: **APPROVED**
  - All 35 canonical cases are represented above; no PASS was inferred from a neighboring workflow.
  - Exact-head run conclusions, structured evidence artifacts, artifact digests, database/restart/replay assertions and production JAR identity were reconciled before credit.
  - The tested production JAR remains byte-identical after the final test-only Tracking fixture corrections.
- Owner/operator sign-off: **APPROVED**
  - Standing release authorization was recorded on PR #18 after the full tested matrix completed green in comment `5246040850`. It authorizes the package worker to complete validation, merge, release and post-merge verification; it does not claim a separate human re-audit of each artifact.
- Overall: **PASS**

This PASS is the audited WP-05 acceptance-matrix result for the production candidate. Package completion still requires the final documentation-only exact-head rerun, independent review, applicable Sentinel startup/restart evidence, normal merge, post-merge `main` verification, verified `v1.0.0` release/assets, and durable handoff publication.
