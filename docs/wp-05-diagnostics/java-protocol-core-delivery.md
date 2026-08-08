# WP-05 diagnostic evidence — Java protocol core/delivery/restart

**Acceptance credit:** NONE

This diagnostic intentionally uses a Mineflayer Java 1.21.11 client with `auth: offline` against a disposable `online-mode=false` Paper server. It exercises real Minecraft protocol, Bukkit `Player`, inventory, command, delivery, persistence, quit/join, and server-restart paths, but it does **not** satisfy WP-05's production Java identity acceptance boundary. It exists only to discover defects before authenticated acceptance is available.

## Exact run
- Source/harness commit: `186ae452bc69bd5efae1c848fe327e7d9164c418`
- Workflow run: `31220039263`
- Job: `93002370539`
- Raw artifact: `9010069874`
- Raw artifact digest: `sha256:0a34945785c4e29e2a75ab95f4f1cc58a75e2612347925e3df4c8a058fcac636`
- Raw artifact expiry: `2026-09-06T21:29:30Z`
- Exact LoreItems RC SHA-256: `3c7b6aa74ee63a4e049c5e09f2bebffe78bf50ea88caaaa3d03b55e941f427c8`
- Mineflayer: `4.35.0`, Java protocol `1.21.11`, offline authentication
- Diagnostic player: `Wp05JavaBot`, offline UUID `6c93357f-027a-313f-a8bb-620b629b318b`
- UTC: `2026-08-07T21:27:24Z` through `2026-08-07T21:29:30Z`

## Path exercised
1. Bot connected over the real Java protocol and became a real Bukkit `Player`.
2. Server supplied a physical diamond sword; bot selected it in the hotbar.
3. Bot issued `/loreitems create acc_diag_core Diagnostic Core` and received the expected success response.
4. Bot issued `/loreitems adopt acc_diag_core` and received the expected success response.
5. Bot issued `/loreitems give acc_diag_core`; delivery completed while online and the bot physically observed two diamond swords.
6. Bot disconnected. Console queued another give to the now-cached offline player.
7. Server stopped cleanly. SQLite contained exactly one active definition, three active instances, and two delivery rows: one `COMPLETED`, one `PENDING`; integrity passed and foreign-key check was empty.
8. Server restarted. The same protocol identity rejoined and received the queued item; physical sword count became three. SQLite then showed both deliveries `COMPLETED`, three instances, integrity OK, no FK violations.
9. Server restarted a second time. The same player rejoined and physical sword count remained exactly three. SQLite remained three instances, two deliveries, two completed, zero review-required; integrity/FK clean.

## Durable observations
### Initial client
- create response: `Created lore definition 'acc_diag_core' from the held item.`
- adopt response: `Adopted the held item into lore definition 'acc_diag_core'.`
- give response: `Lore item queued. It will deliver when the player is online with inventory space.`
- delivery response: `A queued lore item was delivered to your inventory.`
- `SWORD_COUNT_AFTER_ONLINE_GIVE 2`

### Recovery client
- immediate delivery response on join
- `SWORD_COUNT_RECOVERY 3`

### Replay client
- no further delivery response
- `SWORD_COUNT_REPLAY 3`

## Database checkpoints
Before first restart:
- one active definition `acc_diag_core`
- three ACTIVE instances
- delivery states: one `COMPLETED` (`attempt_count=1`), one `PENDING` (`attempt_count=1`)
- integrity `ok`; foreign keys empty

After recovery restart:
- three instances
- both deliveries `COMPLETED`; offline request advanced to `attempt_count=2`
- integrity `ok`; foreign keys empty

After replay restart:
- three instances
- two deliveries total
- two completed deliveries
- zero review-required deliveries
- integrity `ok`; foreign keys empty

## Findings
No LoreItems implementation failure was observed in this diagnostic path. The first execution (`31219619223`) had already succeeded through create/adopt/online give but crashed in its evidence query because the harness incorrectly selected `lifecycle_state` from `lore_definitions`; that column exists on `lore_instances`, not definitions. Commit `186ae452bc69bd5efae1c848fe327e7d9164c418` corrected only the evidence query. The corrected run passed all three lifecycles.

The server logs contain the expected warnings that the diagnostic server is intentionally in offline/insecure mode. That is precisely why this evidence is not accepted as `ACC-ID-001`, `ACC-CORE-*`, or any other WP-05 PASS.

## Conclusion
The exact RC survived the diagnostic create/adopt/online-give/offline-give/restart/replay sequence without item-count drift, duplicate delivery, review-required escalation, SQLite integrity failure, or LoreItems exception. This reduces implementation risk but does not replace authenticated live acceptance.
