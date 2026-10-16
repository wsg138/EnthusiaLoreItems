# Worker J — inherited parent Codacy review (2026-10-16)

## Verified combined-parent baseline

Draft [PR #154](https://github.com/wsg138/EnthusiaLoreItems/pull/154) head `fdef30cc39694993db303889438aed570b66ceb9` includes Worker I's #156 changes **on the draft branch only**, never on main.

- [Combined CI 38809460005](https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38809460005): **FAIL** at exact-head Codacy; 50 capped annotations in `acceptance-harness/scripts/acc-track-phase2-negative-bot.js`. Earlier build/tooling/Lizard checks passed; later jobs were skipped. 50 is a reporting cap, not necessarily a full finding count.
- [Combined genuine Paper/SQLite run 38809460533](https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38809460533): **PASS**, [original evidence artifact 12008376331](https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38809460533/artifacts/12008376331). Paper 1.21.11 build116 / Temurin Java25, separate real Mineflayer-tracked X and Z negative -17|-16 double chests, 54-slot real GUI, true 0→26→27→53→0 physical movement; canonical UUID/physical halves, immutable SQLite receipts (five CONFIRMED_NOW stages, revision 4/6/8/10/12), after disposable restart LAST_CONFIRMED slot0 revision13 and six receipts, zero anomalies in each case.
- [Exact combined-head test JAR 38809459843](https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38809459843): **PASS**.
- ACC-TRACK-008..011 physical evidence is bounded to those cases. No DC-01..DC-48 release clearance.

## Scope of security improvement

`waitFile(name)` and `mark(name)` previously checked a strict anchored list of marker names but then interpolated `name` into filesystem paths under fixed disposable `/tmp/acc-track-phase2`. The guards reject traversal; this is **not demonstrated exploitation**. Nevertheless, their path safety depended on cross-statement regex/dataflow reasoning, triggering two high-severity dynamic-path annotations on original lines 20 and 31.

The Worker J candidate adds two frozen explicit marker-name-to-fixed-file maps. Each marker operation keeps the regex guard, checks map membership using `Object.hasOwn`, and uses only a mapped path in `fs.existsSync`/`fs.writeFileSync`, never an interpolated caller-supplied path. The added negative regression checks all 11 host checkpoint keys and 11 result keys exactly equal their fixed-root filenames, plus existing tests for traversal, near-match, missing receipts, duplicate instance/anomaly, wrong chest half, wrong coordinates and missing LAST_CONFIRMED. Static source tests are distinct from real Paper acceptance.

## Findings and remaining static-profile mismatch

All 50 initial parent annotations were classified individually in `docs/performance/2026-10-15-worker-i-codacy-triage.md`. Current combined-parent scan still reports many Node-versus-browser policy conflicts: `fs` imported in a disposable Node client, runtime-only Mineflayer and vec3 modules installed on CI host, CommonJS `require`, modern `const`/arrow/Promise/async syntax, Node timers and synchronous evidence markers, inapplicable TypeScript parameter-type requirements in plain JavaScript, and an Opera Mini compatibility rule. These are not grounds to rewrite a Node 24 client as obsolete browser JavaScript.

The GitHub emitted annotation text supplies line/severity/messages, **not exact vendor rule IDs**; do not invent such IDs. Any residual genuine issues on new HEAD require separate remediation. A narrowly reviewed Node-specific scanner profile could be proposed to the coordinator if the exact-head CI remains red; **do not** suppress/ignore the file, disable Codacy, relax global security policy, or mislabel a child-only green diff as clean for #154. No `.codacy.yml`, production Java/SQLite, server, credentials, or #103 planner changes.

## Gate

Worker J's new exact-head CI, test-JAR, Paper acceptance and artifact integrity must be listed in the draft PR and #95 when complete. Until then, unexecuted results are **UNVERIFIED**. Issues [#95](https://github.com/wsg138/EnthusiaLoreItems/issues/95) and [#103](https://github.com/wsg138/EnthusiaLoreItems/issues/103) remain OPEN; RELEASE HOLD for full DC-01..48, world-key migration, both half-load orders, all orientations, fault/recovery and AI-OFF performance. No merge to #154/main or deployment without coordinator/owner review.
