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
Cases 008/009 are UNVERIFIED until successful exact-head hosted genuine-Paper
workflow execution. Local synthetic verifier tests do NOT count as physical
cases; missing or fabricated SQLite evidence must fail.

## DC-01 through DC-48 case map

PARTIAL is not PASS for the complete case. UNVERIFIED does not establish
a product regression. Past proof is documented in issues #95 and #103 and
prior draft PRs #135, #137, #140, #142, #143, #145, #151–#153.

| ID | Required scenario | Status / missing proof |
|---|---|---|
| DC-01 | Same chunk | PARTIAL; real 54-slot marker geometry |
| DC-02 | Positive X chunk | PARTIAL; real tracked GUI 0→53 receipts |
| DC-03 | Positive Z chunk | PARTIAL; real tracked GUI 0→53 receipts |
| DC-04 | Negative X chunk | PARTIAL; -1|0 tracked, -17|-16 pending 008 |
| DC-05 | Negative Z chunk | PARTIAL; -1|0 tracked, -17|-16 pending 009 |
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
| DC-20 | GUI 0→53→0 | UNVERIFIED; cases 008/009 awaiting hosted real Paper |
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
