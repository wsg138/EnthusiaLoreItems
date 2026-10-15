# Worker 04: real Paper + SQLite cross-chunk acceptance status

Date: 2026-10-15. Source base: PR #153, SHA
5a73e9d1a5d2890a986a96c3bbbadcf609ee8487.
Issue #95 remains release HOLD; issue #103 historical-key migration remains separate.

## New acceptance

ACC-TRACK-008 uses a newly issued tracked lore sword in a real Mineflayer
client, X -17|-16 at Y70/Z8. ACC-TRACK-009 independently uses a second
tracked sword, Z -17|-16 at Y70/X8. Each opens the genuine 54-slot composite
GUI, deposits slot 0 and physically clicks 0→53→0 from the other half.
Read-only SQLite verification requires one instance, exact world UUID and
ordered chest coordinates, stable side mapping, revision advancement,
immutable ordered CONFIRMED_NOW receipts, zero anomalies and DB integrity.
The same disposable SQLite and plugin JAR must survive shutdown/restart.

Test fixture: Paper 1.21.11 build 116 SHA-256
e708e8c132dc143ffd73528cccb9532e2eb17628b1a0eee74469bf466c7003f8.
Host builds on Java 21; runs Paper under Java 25. Paper 26.2 API conformity
and no-force-load with only one half accessible are separate proof obligations.
**ACC-TRACK-008 and ACC-TRACK-009 passed genuine disposable Paper + SQLite**
on PR #154 commit `91b505058efa4e49c4d7a3cfbf8aa2b0be926c2c`:
https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38691758607
X: CONFIRMED_NOW slot 0 (revision 4), slot 53 (6), slot 0 (8);
restart LAST_CONFIRMED slot 0 (9); 4 immutable receipts; 0 anomalies.
Z: same transition sequence independently; distinct item UUID and key;
4 receipts and 0 anomalies on restart. Source world UUID identical,
physical X/Z canonical coordinates different. Original job log and artifact
`11951509335` retain the evidence. **A later commit must re-run and pass
before exact-final-head acceptance can be credited.** Local synthetic
verifier tests are not physical cases.

## DC-01 through DC-48 case map

PARTIAL is not PASS for the complete case. UNVERIFIED does not establish
a product regression. Past proof is documented in issues #95 and #103 and
prior draft PRs #135, #137, #140, #142, #143, #145, #151–#153.

| ID | Required scenario | Status / missing proof |
|---|---|---|
| DC-01 | Same chunk | PARTIAL; real 54-slot marker geometry |
| DC-02 | Positive X chunk | PARTIAL; real tracked GUI 0→53 receipts |
| DC-03 | Positive Z chunk | PARTIAL; real tracked GUI 0→53 receipts |
| DC-04 | Negative X chunk | PARTIAL; -1|0 plus real -17|-16 tracked PASS at initial 91b head; other variants missing |
| DC-05 | Negative Z chunk | PARTIAL; -1|0 plus real -17|-16 tracked PASS at initial 91b head; other variants missing |
| DC-06 | All orientations | UNVERIFIED; limited chest facings |
| DC-07 | Reverse placement order | UNVERIFIED; no independent tracked movement |
| DC-08 | All 54 half slots | PARTIAL; vanilla markers, not 54 PDC UUIDs |
| DC-09 | First half load then second | UNVERIFIED; exact natural order |
| DC-10 | Second half load then first | UNVERIFIED; exact natural order |
| DC-11 | First loaded only | PARTIAL; native production refusal, not full PDC durability |
| DC-12 | Second loaded only | PARTIAL; native production refusal, not full PDC durability |
| DC-13 | Half unload while GUI open | UNVERIFIED; actual unload proof required |
| DC-14 | Natural reload and reopen | PARTIAL; separate reload and tracked restart |
| DC-15 | Pending callback at unload | UNVERIFIED; dirty generation race |
| DC-16 | Single-to-double | PARTIAL; native physical tests, not tracked transition |
| DC-17 | Double-to-single | PARTIAL; native physical tests, not tracked transition |
| DC-18 | Break/replace while hopper pulls | UNVERIFIED |
| DC-19 | No implicit load/ticket | PARTIAL; native missing-half refusal only |
| DC-20 | GUI 0→53→0 | PARTIAL; real X/Z -17|-16 reverse GUI PASS at 91b, other orientations unverified |
| DC-21 | Shift-click | UNVERIFIED; double-chest movement proof |
| DC-22 | Double-click collect | UNVERIFIED |
| DC-23 | Drag | UNVERIFIED |
| DC-24 | Number-key swap | UNVERIFIED |
| DC-25 | Cursor/hotbar | PARTIAL; general player tracking only |
| DC-26 | Offhand | PARTIAL; general player tracking only |
| DC-27 | Armor | PARTIAL; general player tracking only |
| DC-28 | Ender chest | PARTIAL; general player tracking only |
| DC-29 | Cancelled interaction | UNVERIFIED |
| DC-30 | Logout/rejoin | PARTIAL; general player tracking only |
| DC-31 | Two players opposite halves | UNVERIFIED |
| DC-32 | Hopper push both halves | UNVERIFIED |
| DC-33 | Hopper pull both halves | UNVERIFIED |
| DC-34 | Shulker in slot 53 | UNVERIFIED |
| DC-35 | Bundle in slot 53 | UNVERIFIED |
| DC-36 | Nested chest-to-player roundtrip | PARTIAL; other nested storage only |
| DC-37 | Duplicate ID at 0 and 53 | UNVERIFIED |
| DC-38 | Duplicate while one half unseen | UNVERIFIED |
| DC-39 | Root 257+ continuation | PARTIAL; synthetic budgets only |
| DC-40 | World UUID and block type | PARTIAL; canonical keys and tests |
| DC-41 | Death/drop/pickup | PARTIAL; separate tracked player test |
| DC-42 | SQLite mid-write crash | PARTIAL; real WAL lock/reopen on #153, not kill |
| DC-43 | LAST_CONFIRMED unloaded slot | PARTIAL; prior real X/Z restart evidence |
| DC-44 | Ordinary hopper fast path | PARTIAL; previous perf work, no matched run |
| DC-45 | No recurring full chunk scan | UNVERIFIED |
| DC-46 | 1,200 tick test | UNVERIFIED |
| DC-47 | Percentiles and queue/memory | UNVERIFIED |
| DC-48 | Alternating natural unload/retry | UNVERIFIED |

## Reproducible evidence gate

The phase2 GitHub workflow stores immutable source-head.txt, jar-sha256.txt,
paper-sha256.txt, java-version.txt, server.log, server-restart.log,
bot.log, bot-stdout.log and case-results.txt. PASS requires matching
exact-head hashes, actual real-client clicks and exact per-instance SQLite
histories in the original workflow artifact. Failures remain failures; no
manually generated JSON manifest, plain 54-slot marker count, or synthetic
test can substitute for native PDC movement proof.

Previous PR #153 WP05 tracking workflow:
https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38601437305

Explicit exclusions: no live SMP or TEST install, no SFTP, no production
Java/SQLite changes, no migration #103, no owner release authorization.
Do not merge this stacked draft without independent review.

## Hosted CI safety finding

At original physical-PASS SHA 91b5050, hosted CI run
https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38691758514
failed at Codacy. The first 50 emitted annotations were on the NEW
Mineflayer test-only JavaScript client. These include genuinely actionable
non-literal `require` paths and filesystem marker validation, alongside
Node builtin, ES2015 and async syntax findings that appear to use a
non-Node parser/language profile. A follow-up change hardcodes module paths
and bounds marker names to exact allowlisted formats. The final head must
be rechecked. CI is **RED** until remaining findings are triaged
individually; no broad exclusion or disablement is authorized.


## Worker I independent follow-up (unverified until hosted exact-head success)

New stacked test-only worker branch test/acc-track-phase2-worker-i-20261015
extends both negative-boundary tracked client scenarios from original
0→53→0 to **0→26→27→53→0**. The two new bounded physical-proof
subcases are named ACC-TRACK-010 (X -17|-16 26→27 seam) and
ACC-TRACK-011 (Z -17|-16 26→27 seam). Their 54-slot real-client
checkpoints at each step require new immutable canonical SQLite
CONFIRMED_NOW receipts at 26 and 27 as well as 0 and 53, exact
unmodified physical UUID/world/coordinated pair, one tracked
instance, no anomaly and restart LAST_CONFIRMED at slot0 when natural
unload occurs. Neither synthetic unit tests nor the old #154 physical
run can validate these NEW intermediate movements.

This extension covers only two specific boundary/orientation/half
seams and does NOT prove DC-08 all 54 tracked slots, DC-06 all
orientations, DC-09/10 unloaded-half load orders, or full DC-01..DC-48.
The CI fixture explicitly holds both halves loaded during movement.
The original #154 008/009 run remains historical evidence for
0→53→0; its old artifacts must NOT be presented as proof of 010/011.
The stacked child workflow now targets PRs into #154 as well as main
and #153, but source HEAD, JAR and Paper SHA must still match for credit.

Worker I independent Codacy per-annotation triage is documented in
2026-10-15-worker-i-codacy-triage.md (50 exact findings on parent
head). No broad Codacy exclusions are introduced. Re-run check on the
new child head before any static-clean claim.


## Worker I hosted physical proof — exact first child head c42ab5f9

The exact-head genuine disposable Paper 1.21.11 build 116 / Java 25
workflow completed **SUCCESS** at
https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38805614657,
source SHA c42ab5f94c6d5efb568b7980ce47248c5616c22a.
Original workflow evidence artifact:
https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38805614657/artifacts/12005149427.

Real non-mock 54-slot Mineflayer click + read-only SQLite receipts:

- X (-17,70,8)|(-16,70,8), ACC-TRACK-008/010:
  0 CONFIRMED_NOW revision 4, 26 revision 6, 27 revision 8,
  53 revision 10, 0 revision 12; six immutable receipts at
  LAST_CONFIRMED slot 0 revision 13 after disposable Paper restart.
  Same instance and canonical world UUID/left marker throughout;
  0 anomalies.
- Z (8,70,-17)|(8,70,-16), ACC-TRACK-009/011:
  independent issued instance, 0/26/27/53/0 revisions 4/6/8/10/12,
  six receipts, LAST_CONFIRMED revision 13 at slot 0 after restart,
  0 anomalies; canonical Z key and UUID verified independently.
- Host run explicitly checked actual 54-slot GUI and restart persisted
  the same disposable SQLite DB and server plugin JAR.
- Source CI https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38805614630
  **SUCCESS**, including the exact-head hosted Codacy check (passed)
  and Codacy-Lizard threshold check; exact-head test JAR
  https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38805614910
  **SUCCESS**.

A further targeted source-only regex literal-marker fix is being
applied after this first green head. The first-head workflow is **not**
evidence for any later SHA; new HEAD must independently rerun.
No release approval or full DC-01..DC-48 PASS assertion.


## Intermediate child HEAD 52602696 evidence and follow-up

At SHA 52602696bc9ce17136adc0cc194bf3e885a3624d, genuine
disposable Paper/SQLite
https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38806812299
completed real X/Z 0→26→27→53→0 movement and restart successfully.
Both original tracked instances have independent 6 immutable receipts,
zero anomalies, LAST_CONFIRMED slot0 revision13; original artifact
https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38806812299/artifacts/12006078444.
Exact-head test JAR
https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38806812277
also passed. **General CI FAILED** at that SHA on six new Python
subprocess-launch Codacy findings introduced by the checkpoint
regression, despite existing production, core unit and real Paper tests
passing. The following commit removes process launch entirely and
keeps the regex name acceptance/rejection assertions in-process.
No Codacy exemption or release approval is implied.
