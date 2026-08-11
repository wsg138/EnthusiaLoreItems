# WP-05 acceptance evidence index

WP-05 uses this committed ledger for audited acceptance results. A successful workflow by itself is not a PASS: each case below was reconciled against the case contract, exact-head workflow result, structured evidence bundle, and release-candidate identity before being credited.

## Final audited candidate

- Package: **WP-05 — live acceptance and production release**
- Tested source head: `b978bffe5ba5c93386a815f040432ccd0357c2ed`
- Production version: **1.0.0**
- Paper: **1.21.11 build 116**
- Java: **21**
- Exact LoreItems JAR SHA-256: `7c862b0ae545d710a33267ad6e19a4ae26d97323e97f40707c1475c9f9ba7063`
- CI run: `31547241447`
- CI verification artifact: `wp04-verification-b978bffe5ba5c93386a815f040432ccd0357c2ed` / artifact `9122981243` / digest `sha256:5515f200e7601b879c5d19ac56c89332733aa950c1cb4b4ef85cf4b4eb89b167`
- Production JAR artifact: `enthusialoreitems-plugin` / artifact `9122981664` / digest `sha256:2d3948055729c8907d29dec3a03ead63e7353b14124a8707dab6b0e175934eee`
- The post-implementation configuration-evidence hardening and Tracking display-fixture correction changed acceptance/evidence workflow behavior only. CI rebuilt the same production JAR SHA-256 as the immediately preceding green candidate.

## Canonical case ledger

| Case | Result | GitHub run | Evidence artifact | Audit finding |
|---|---|---:|---|---|
| ACC-ENV-001 | PASS | `31547241444` | `9123010571` / `sha256:337f4f5f5299754caf9876bbe3ab5d8449533bba353a135a93ad5381b9bec1f2` | Required environment starts correctly and degraded optional integrations remain non-fatal. |
| ACC-ID-001 | PASS | `31547241490` | `9123022216` / `sha256:776ccddb2fb77c83ddcc41bc88206209d5cad7a9600b5ed786ee308fa1d77893` | Java online/offline identity and target resolution contract passed. |
| ACC-ID-002 | PASS | `31547241422` | `9123014531` / `sha256:58443e0aba7d88b3ef864de933631c6797db086ba761e4739dd1953975579c8c` | Floodgate/server-visible identity, literal `*` names, UUID/name paths, cached-offline and never-joined behavior passed. |
| ACC-CORE-001 | PASS | `31547241490` | `9123022216` | Definition creation and durable identity passed. |
| ACC-CORE-002 | PASS | `31547241490` | `9123022216` | Direct delivery and unique-instance persistence passed. |
| ACC-CORE-003 | PASS | `31547241490` | `9123022216` | Restart/replay preserved canonical state without duplicate application. |
| ACC-CORE-004 | PASS | `31547241490` | `9123022216` | Audit/database integrity and physical-state reconciliation passed. |
| ACC-CORE-005 | PASS | `31547241443` | `9122988638` / `sha256:f25d4fe1f5ed434b1ef14b98ea86960c4adb8f913b9a796f75620cbb98a6656c` | Full inventory queues delivery and later completes exactly once when space becomes available. |
| ACC-EDIT-001 | PASS | `31547241420` | `9122971401` / `sha256:e36eeae71d62233ac0558e75f16d1dc36671e81b1a627fb58a7d252dd0623ad3` | Editor creates an immutable next revision through the supported UI/chat path. |
| ACC-EDIT-002 | PASS | `31547241420` | `9122971401` | Editor confirmation/cancel and revision integrity contract passed. |
| ACC-EDIT-003 | PASS | `31547241403` | `9123048699` / `sha256:b3bf6c6e4c0d62765fcc2e1598a875a87fb3ec0ecf41dcec9930dde2d582e9d5` | Revision rollout converged across player, container, nested, display, dropped, offline, unloaded and restart paths with stable instance IDs and replay safety. |
| ACC-TRACK-001 | PASS | `31547241446` | `9123002475` / `sha256:93683a8b8f07cac03be44a9e2d851c04f7856188e990b0e4d37530a5113812c3` | Player inventory/offhand/armor/cursor/Ender/offline/rejoin continuity passed. |
| ACC-TRACK-002 | PASS | `31547241446` | `9123002475` | Chest/hopper/nested shulker+bundle tracking, allowed/restricted policy, unload/reload retention and authoritative close reconciliation passed. |
| ACC-TRACK-003 | PASS | `31547241446` | `9123002475` | Natural drop/pickup, real client item-frame/glow-frame/armor-stand placement, death/drop and natural chunk lifecycle passed. |
| ACC-PROT-001 | PASS | `31547241401` | `9122978526` / `sha256:f79dfde361c63b8500aba6d3b7c6e2392b10d89321c3efa16cf31cf930d4e053` | Protected tracked-item movement/use and environmental-loss restrictions passed. |
| ACC-PROT-002 | PASS | `31547241401`, `31547241457` | `9122978526`, `9122974416` / conversion digest `sha256:bee62a96caa9e258f4ce186e3eab691b617feacdb2b5409ecb19d4a0d50f3401` | Consume/mob/void and crafting/smelting/grindstone/smithing conversion protection preserve lore-item identity and block unsafe paths. |
| ACC-ANOM-001 | PASS | `31547241438` | `9123099217` / `sha256:e5eb4eaa5bdcbb5af4e9cdabce1457e314cd6fdae61075d416914d5373e01114` | Duplicate/malformed detection, immediate and five-minute warning, staff inspection and supported resolution passed while physical copies were preserved. |
| ACC-ANOM-002 | PASS | `31547241512` | `9123029341` / `sha256:fb68053a52d0125e884f2083916aa9d5dea9cebe94e8dc0e850877dba2b8433c` | Ambiguous post-physical mutation state is quarantined/recovered after inspection without blind repeat. |
| ACC-DEST-001 | PASS | `31547241400` | `9122975363` / `sha256:a6906028faed7c33fe91c78a66c49ca25d0efb98735a53dda7327c91cd47f774` | Exact instance removal targets only the requested instance and leaves the sibling active across restart. |
| ACC-DEST-002 | PASS | `31547241411` | `9122975108` / `sha256:07dbf3a54d029ef5cec0155e14e4abc9aa5961eff5ee8ac5ea2e47870b608282` | Destructive lifecycle/restart behavior passed without unsupported global Bukkit `/reload`. |
| ACC-DEST-003 | PASS | `31547241496` | `9122974822` / `sha256:9fef9c2075c83a8cb390bddbaa33a3842ae771de4191fdf7a5b262192f7ca748` | Full delete plus late physical-copy/tombstone handling passed and reopened/completed late work safely. |
| ACC-DEST-004 | PASS | `31547241411` | `9122975108` | Destructive replay/restart remains idempotent and durable. |
| ACC-DIST-001 | PASS | `31547241409` | `9123008436` / `sha256:da4023d805bdbe3f270f9ef5ff7eeaff6ebf125e6cc3c8ba0995244d851abd51` | Campaign creation and deterministic recipient targeting passed. |
| ACC-DIST-002 | PASS | `31547241409`, `31547241440` | `9123008436`, `9123009478` / Floodgate digest `sha256:4314f4e6ad46330484f5d9bc4095b554aed8c4b413ed40e4bb841cd1d3b92697` | Java and Floodgate recipients resolve and receive exactly once across online/offline/restart paths. |
| ACC-DIST-003 | PASS | `31547241409` | `9123008436` | Resume/restart campaign state does not duplicate completed delivery. |
| ACC-DIST-004 | PASS | `31547241409` | `9123008436` | Partial/full-inventory campaign work remains queued and later converges safely. |
| ACC-DIST-005 | PASS | `31547241409`, `31547241440` | `9123008436`, `9123009478` | Campaign audit/status and mixed Java/Floodgate lifecycle remain durable and idempotent. |
| ACC-API-001 | PASS | `31547241476` | `9123008156` / `sha256:9808542596831bcb1ac73049cf7ec06e3d37a2e41624d06dc4d832d74eabbbcb` | Public API accepted/replay/unknown/validation/unavailable/recovery contract passed with one durable delivery per accepted operation. |
| ACC-LIFE-001 | PASS | `31547241450`, `31547241405` | `9122991789`, `9122991317` / config digest `sha256:d1d1a47b33530f06a9d2046f93f194faec94f29a8f380a9da5452dc354021d68`, mixed digest `sha256:0cb7ea3938c7cae74780a1c5b0986c1c05237b4cd2373db0de4ff3ef362598f2` | Supported atomic `/loreitems reload` applies valid configuration, rejects invalid configuration while retaining last-known-good behavior, and does not lose queued work. |
| ACC-LIFE-002 | PASS | `31547241411`, `31547241405` | `9122975108`, `9122991317` | Clean shutdown/restart with mixed pending work drains/replays safely and preserves integrity. |
| ACC-OPS-001 | PASS | `31547241444` | `9123010571` | Startup/degraded optional integration behavior passed. |
| ACC-OPS-002 | PASS | `31547241430` | `9122974424` / `sha256:bf958e407422c08926d56250e9e517d8636c81740cad552acd03c250e9e7ab56` | Offline backup produced a byte-identical pre-start restore; post-backup changes were absent and pending/deleted/campaign state recovered with clean integrity/FKs. |
| ACC-OPS-003 | PASS | `31547241430` | `9122959777` / `sha256:52bd3e359321715c368f37832b4835c13d8ed3999d39afe828e8780f8d87d4ab` | Release rollback restored the compatible prior full directory/JAR while preserving the current deployment separately; integrity/FKs passed. |
| ACC-OPS-004 | PASS | `31547241416` | `9122961480` / `sha256:1674f0606edc86c5d22b77ab34b5198ef4a7cedaee2a84a8ef32df11b3255637` | Sustained load remains bounded and completes accepted work without silent loss. |
| ACC-OPS-005 | PASS | `31547241416` | `9122961480` | Queue saturation/backpressure is explicit, bounded and recoverable. |

## Additional review evidence

- Mutation review contract run `31547241421`, artifact `9122942917`, digest `sha256:a888d88d5be9067007eb6eb1c3d8d28f9f76449e90af398cde7a6b2411e8db4e`, passed on the tested candidate. Its stored Gradle XML reports include the mutation-review command, pending-mutation store, atomic rollback, idempotency and related persistence suites.
- External Codacy static analysis completed successfully on `b978bffe5ba5c93386a815f040432ccd0357c2ed` with zero observed annotations.
- PR #24 independently identified two major acceptance-evidence risks before this candidate: an unsupported `audit_log` assertion and optimization-removable Python `assert` gates. The invalid audit assertion was removed rather than inventing an out-of-contract product audit event; the valid fail-open assertion finding was fixed with explicit fail-closed checks and required evidence upload failure. Both review threads are resolved.
- The two failed Tracking attempts on predecessor `025a9f0f820c843698ae60c52fe0a19b26f43c24` are intentionally not credited. They exposed an unstable block-attached display fixture before any LoreItems tracking assertion. Candidate `b978bffe...` corrects only the acceptance fixture coordinates to the deterministic entity-face positions observed by the real client; the credited Tracking run then passed all main and restricted phases while the production JAR remained byte-identical.
- GitHub Actions run pages and this committed ledger are permanent repository records. Raw uploaded artifacts have their configured retention windows; the case adjudications, exact run IDs, artifact IDs and content digests above remain committed here after raw archive expiry.

## Release gate adjudication

- Evidence audit: **APPROVED**
  - All 35 canonical cases are represented above; no PASS was inferred from a neighboring workflow.
  - All listed exact-candidate workflow conclusions are successful.
  - The current evidence archives were separately reconciled against case IDs, expected/actual behavior, exact source `b978bffe5ba5c93386a815f040432ccd0357c2ed`, exact production JAR `7c862b0ae545d710a33267ad6e19a4ae26d97323e97f40707c1475c9f9ba7063`, restart/replay/database assertions, GitHub artifact identities and content digests before credit.
  - CI independently rebuilt the candidate and demonstrated the same production JAR SHA-256 after the test-only acceptance fixes.
- Owner/operator sign-off: **APPROVED**
  - Standing release authorization was recorded on PR #18 by repository owner `wsg138` after the full tested matrix completed green in comment `5246040850`. It authorizes the package worker to complete validation, merge, release and post-merge verification; it does not claim a separate human re-audit of each artifact.
- Overall audited candidate: **PASS**

release_ready: APPROVED

This release marker opens only the repository's documentation gate for the next exact-head CI artifact. It does not itself complete WP-05. The documentation-only head containing this ledger still requires its own exact-head matrix/CI/Codacy verification, independent final-delta review, applicable Sentinel startup/restart evidence, normal merge, post-merge `main` verification, verified production `v1.0.0` release/assets, and durable completion publication.
