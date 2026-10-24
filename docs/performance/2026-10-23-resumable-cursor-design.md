# Resumable LoreItems scan: immutable state-only contract (2026-10-23)

**Status: development-only contract; no Paper scanner or SQLite integration, no physical acceptance.**
Owner: Worker 1, issue #83. Initial parent: draft #165 at
`c878b02f01b1084f2922d3b913ca693b3eeb5d2d` (11 commits ahead of
`main` `86999c032eaf658d6e66223d73aedf58cbd6cee2`).
The existing W08 experiment #98, `PaperBoundedInventoryContinuation`, is not changed.

## Problem and exact guarantees

The original `PaperScanLimit(256)` stops one synchronous pass. A 257th
root or a later nested leaf is not thereby proven absent. Repeatedly starting
again at slot zero indefinitely starves leaves after the first page, and
previously submitted observations cannot retroactively become authoritative
when a later page truncates. Draft #169 is development-integrated in #165;
drafts #168, #170, #171 are **separate**. Source-level fixes and old test
coverage do not prove same-tick physical safety or durable SQL acknowledgement.

`PaperScanContinuationCursor` defines *pure state*, not inventory traversal:

- `Scope(worldId UUID, canonicalSourceKey, loadGeneration, scanGeneration,
  geometry, rootSlots)` fences the origin and topology. Double-chest geometry
  must be canonical and include both half coordinates and their applicable
  load generations. The world ID is the runtime UUID, not world display name.
- `Stamp(scope, inventoryVersion, dirtyEpoch, accessible)` is a producer-
  supplied immutable mutation fence. **The producer must establish that every
  relevant inventory/slot/nested mutation increments version or dirty epoch.**
  An event counter that misses plugin or external mutations is insufficient.
- `Cursor(stamp, path, visits, pages, complete)` has only serializable
  immutable values. A path is a root slot and at most 8 nested shulker/bundle
  offsets with sibling widths. There are no Bukkit `World`, `Chunk`,
  `Inventory`, `ItemStack`, `Block`, metadata, or held callbacks.
- Each `advance` accepts at most 256 visited work units. Its next coordinate
  must advance monotonically; mismatched world/scope/load/scan/inventory
  version, dirty epoch or pair geometry returns `INVALIDATED`. Unload is
  `UNAVAILABLE`: drop the cursor and restart at root zero on *natural*
  reload using a **new** load generation. A finished cursor returns
  `REPLAYED` when reused.
- `INCOMPLETE` means more work remains; `COMPLETE` means only that the
  scanner **reported traversal complete**. Neither state, including a
  multi-page COMPLETE, enables unique presence/absence or a durable ACK:
  `Outcome.authoritativeEligible()` deliberately always returns false.
  Any authoritative upgrade requires **separately proved full coherent
  coverage**, duplicate/copy handling and successful persistence.
- `FairRetries` uses a bounded insertion-ordered map keyed by
  (world UUID, source key), with one latest generation/version per source.
  `next()` rotates across sources without consuming failed admission, so
  repeated queue saturation cannot silently remove a pending cursor.
  Older generation/version and out-of-order pages cannot clobber newer state.
  A full map sets a durable-in-process `rediscoveryRequired` flag; only
  a verified full natural-loaded source discovery may clear it. This flag
  needs an external full-sweep producer, and does not itself scan or persist
  overflowed sources.

## Important trust and boundedness boundaries

1. This is **not** an installed scanner. The old scanner still truncates and
   the existing queue has not been connected. Exact >256 eventual catch-up
   requires an integration worker to wire root/nested page reads into the
   load/dirty ledger, schedule fair retry and restart from saved paths.
2. Java serialization proves portable cursor value shape, **not** safe
   persistence across server/plugin restart. Reuse after restart requires
   reconstructing load-generation, source identity and mutation-fence truth;
   until proved, discard old cursors and perform natural-load rediscovery.
3. `COMPLETE` trusts the caller's report of fully advancing *all root and
   nested children*. The pure state contract cannot verify Bukkit reads,
   double-chest half coverage, mutation provenance or a SQLite commit. Treat
   a malformed PDC decode, inaccessible side, moved slot or cancelled/ambiguous
   event as unresolved; do not assert absence from an empty partial page.
4. The retry map is bounded, but it holds one `Cursor` per pending source
   plus one global overflow flag. The global discovery must be separately
   fair, bounded per tick and account for continuously changing loaded sets;
   **no force loads**. A process restart must rebuild outstanding dirty
   work from a safe, persistent source or natural-load discovery.
5. The page visit budget is a contract for the consuming scanner. Integrators
   must charge *every* relevant root/leaf inspection and pre-probe, including
   nested metadata PDC evidence, before decoding. Existing #168/#171 budget
   changes and #169's truncated short-circuit should be reconciled without
   resetting the budget each time an incomplete page is retried.
6. The `acknowledge(completed)` operation only removes a matching, recorded
   terminal cursor and refuses a duplicate. It must be called **only after
   external durable commit/ACK confirmation**. The class itself does no SQL,
   and cannot prove caller behavior, identify copies, or undo an earlier
   premature authoritative observation.

## Initial tests and limitations

`PaperScanContinuationCursorTest` covers 257 and 701 root boundaries,
a nested slot-53 shulker/bundle path, serializability, multiple worlds,
load-generation/geometry/version/dirty mismatch, unloaded/reloaded origin,
out-of-order page attempts, terminal replay, retry starvation prevention,
capacity overflow and one-time matching acknowledgement. These are **pure
JVM contract tests**: they do not establish real Paper 26.2/Java 25 timings,
double-chest DC-01..48, loaded-only behavior, event cancellation ordering,
admission-to-durable-ACK guarantees, or matched AI-OFF SMP MSPT.

## Proposed separately claimed integration order

1. Integrator reconciles this pure contract with W08 #98's nested traversal
   and current candidate #165; choose one canonical API and preserve new tests.
   Check immutable same-prefix nested widths against real source geometry,
   including both double-chest chunk generations.
2. Scanner-owner worker integrates #168/#171 work-unit charging and #169
   short-circuit; produces page outcomes, buffered observations and an
   explicit incomplete/unknown signal for every uniqueness caller. No
   earlier-page authoritative dispatch; duplicates must remain separate.
3. Queue/ledger owner wires the bounded fair retries and global overflow
   rediscovery through naturally accessible, currently loaded sources,
   including unload/reload and shutdown semantics.
4. Persistence owner implements generation/version compare-and-set, durable
   ACK/retry and exact-coherent-snapshot promotion; SQLite failures must
   keep dirty state, not claim confirmed absence or successful scan.
5. Independent integration acceptance: focused and full Gradle tests,
   exact-head CI/Codacy, disposable Paper 26.2 on Java 25, 54-slot and
   nested mutation fixtures, saturated retries, unload/reload, restart,
   SQL WAL-safe recovery, and matched AI-OFF MSPT before release review.

**Release HOLD:** no `main` merge, TEST/SMP install, SFTP/SQLite operations,
restart, item edits, network changes, or paid AI inference in this scope.
