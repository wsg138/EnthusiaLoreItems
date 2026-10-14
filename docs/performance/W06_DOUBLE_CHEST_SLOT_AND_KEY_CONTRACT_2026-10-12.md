# W06 double chest identity, slot and key contract

Integration base: PR #102 commit 2e38abe86de4807447b2e7d2dceaf3d694bff2d1. Draft only; not a release.

## Scanning contract

PaperPhysicalInventorySnapshot.capture(source) records a reload-safe reference. It does NOT authorize enumerating the original source inventory using snapshot.key(): a 27-slot physical half can have a 54-slot double-chest key.

Instead use PaperPhysicalInventorySnapshot.captureResolved(source, plugin), obtaining ResolvedView(snapshot, inventory). For both the physical left or right half and the GUI, the inventory is a verified 54-slot DoubleChestInventory. For deferred work retain only snapshot and call snapshot.resolve(plugin) at the execution tick. Missing or unloaded halves remain unresolved; do not manufacture PRESENT, authoritative uniqueness, or absence.

The pure functions PaperDoubleChestIdentity.combinedSlot(pair, worldUUID, halfPosition, localSlot) and physicalSlot(pair, combinedSlot) provide reversible slot mapping. They do not read worlds or establish PRESENT; call resolve and check current geometry before use.

## Physical example: left (15,70,0), right (16,70,0), facing NORTH

| Source | Local half slot | Canonical GUI slot | Persisted slot path |
| --- | ---: | ---: | --- |
| Left chest | 0 | 0 | slot:0 |
| Left chest | 26 | 26 | slot:26 |
| Right chest | 0 | 27 | slot:27 |
| Right chest | 26 | 53 | slot:53 |
| GUI | 0 | 0 | slot:0 |
| GUI | 26 | 26 | slot:26 |
| GUI | 27 | 27 | slot:27 |
| GUI | 53 | 53 | slot:53 |

Key: double-chest:<worldUUID>:15:70:0|16:70:0:left=0.

When the physical LEFT half has the higher coordinate, suffix left=1 preserves that orientation and the first 27 GUI slots belong to the higher-coordinate half. Crossing negative X/Z chunk borders must use floor arithmetic (x >> 4, z >> 4). Two distinct items in opposite half-local slot 0 produce slot:0 and slot:27, not aliases; repeatedly visiting the same combined inventory must not create artificial duplicate evidence.

## Other worker integration

W07 chunk scanning: A complete pair requires both chunks naturally loaded, valid reciprocal chest geometry, a verified full 54-slot inventory and complete bounded traversal. Never label a 27-slot read with a paired key. A same-tick cached failure must remain failed.

W08 continuation: pair geometry/key and generation fence every page. A partial page is reconciliation only; do not count an inaccessible half as covered. Player cursor, shift-click, drag and cancellation outcomes require post-event resolution.

W09 database: PaperPhysicalLocationKeys.parse has distinct typed LegacyBlock, UuidBlock and UuidPair results. It does NOT migrate, collapse or guess world-name-based history. Ambiguous past keys and WAL-safe recovery remain issue #103.

W10 validation: the seven currently failing DoubleChestCrossChunkAcceptanceTest scenarios use invalid synthetic paired inventories; replace fixtures with actual DoubleChestInventory plus two real chest halves, or prove behavior in disposable Paper. Do not lower production identity checks to satisfy fake fixtures.

## Release holds

No main merge, live deploy, SFTP, Qwen or SQLite operation was authorized. Require Paper 26.2 / Java 25 disposable evidence for both axes and negative boundaries, hopper both halves, player GUI/cursor, break/rejoin/reorientation, natural unload/reload, cancellation, duplicates, DB failure and rollback. Existing historic location evidence must remain accessible.
