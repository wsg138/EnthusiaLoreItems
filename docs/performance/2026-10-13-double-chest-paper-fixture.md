# Disposable Paper double-chest fixture — first real runtime capability

**Scope:** source-only, test-harness module. Not in `plugin:shadowJar`, not deployed,
and not trusted by the live Sentinel controller. Requires independently verified
Paper/Java and a disposable world. The test helper exists in `:acceptance-harness`
and must be explicitly installed alongside the candidate LoreItems JAR in the
isolated test runtime.

## Exact console commands

Once the existing `WP05AcceptanceHarness` plugin is enabled on a disposable
Paper server, use **only** the fixed supported commands:

```text
wp05accept doublechest setup same
wp05accept doublechest setup xplus
wp05accept doublechest setup zplus
wp05accept doublechest setup xminus
wp05accept doublechest setup zminus
wp05accept doublechest inspect xplus
```

- `same`: first chest `(8,70,8)`, second `(9,70,8)`.
- `xplus`: `(15,70,8)` + `(16,70,8)`, X chunks 0/1.
- `zplus`: `(8,70,15)` + `(8,70,16)`, Z chunks 0/1.
- `xminus`: `(-1,70,8)` + `(0,70,8)`, X chunks -1/0.
- `zminus`: `(8,70,-1)` + `(8,70,0)`, Z chunks -1/0.

The command **checks both chunks are already loaded** using
`World.isChunkLoaded(chunkX,chunkZ)` *before* any `World.getBlockAt`,
state, or inventory call. If either chunk is inaccessible, it reports
`WP05_DOUBLE_CHEST_UNRESOLVED` and must not try to load the missing half.
It never calls `getChunkAt`, `loadChunk`, or `setChunkForceLoaded`.
The fixture refuses to replace unrelated blocks. It creates a real pair
with explicit `LEFT` and `RIGHT` Chest block data and checks that Paper
exposes `DoubleChestInventory`, both 27-slot halves, a 54-slot composite,
and consistent boundaries at indices 0,26,27,53. It emits the world UUID,
both block coordinates, and both chunk keys on success.

**Execution prerequisite:** Use a real, disposable world with the relevant
chunks already naturally loaded (for example through controlled test-player
proximity). It is deliberately not permitted to manufacture an unloaded
neighbor chunk. A reported `UNRESOLVED` is a valid blocked observation,
**not** a test pass.

## Important limits

This is **topology/guard proof only**. The helper does **not** create a
tracked LoreItems instance or move one through chest inventories, so the
correctness of item identity, cursor movement, hoppers, SQLite acknowledgments,
LAST_CONFIRMED, partial scans, or cross-chunk lifecycle is **not proved**.
The 48-case release matrix from
[issue #95](https://github.com/wsg138/EnthusiaLoreItems/issues/95)
remains **NOT RUN** pending full post-event/SQLite evidence.

This helper should become one of the trusted primitives of the separate
proposed Sentinel Paper executor only after:
1. reviewed staging-source fixture hash/identity restrictions;
2. independent real Paper 26.2 Java25 topology run;
3. a test-harness JAR staged through trusted Sentinel infrastructure, not a
   repository-controlled shell or untrusted URL;
4. explicit review and trusted allowlist/policy modification in a distinct PR.

Do not deploy the acceptance helper to the production SMP. No changes to
Sentinel policy, live queue, main plugin JAR, SFTP, or SQLite are authorized
by this fixture.
