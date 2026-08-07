# WP-05 manual live acceptance matrix

This matrix is produced by WP-04 for execution in WP-05. It is a test specification, not evidence that live behavior has already passed. Every case must be executed against the **exact final release-candidate jar** and then repeated on the final WP-05 build after the last defect fix.

## Evidence contract for every case

Every case record committed by WP-05 must include:

- case ID and final `PASS`/`FAIL`/`BLOCKED` result;
- UTC start/end timestamps;
- server implementation and exact build;
- Java version;
- plugin version;
- tested jar SHA-256 and source/release commit;
- SQLite schema version;
- complete relevant configuration or a hash plus committed configuration fixture;
- test account UUIDs/names, preserving any `*` Floodgate prefix;
- exact steps actually performed;
- expected result and actual result;
- relevant console log excerpt/file reference;
- relevant database query/result or durable admin-status evidence;
- relevant queue/latency/rate metrics where the case exercises background work;
- physical evidence where applicable (inventory/container/entity state);
- cleanup performed;
- rollback action if the case fails;
- permanent GitHub evidence URL or committed path and hashes for any external/raw artifact.

Screenshots may supplement but never replace durable/database/log evidence. Hidden instance UUIDs must be redacted from public evidence unless the repository location is access-controlled and the diagnostic value is required.

## Shared prerequisites

Unless a case says otherwise:

1. Use Java 21 and the designated Paper/Leaf 1.21.11-compatible acceptance server with Geyser/Floodgate enabled.
2. Deploy the exact RC jar whose SHA-256 matches the WP-04 GitHub prerelease asset.
3. Begin from a verified backup/restore point with SQLite integrity passing.
4. Use disposable acceptance definitions/items/accounts where destructive behavior is exercised.
5. Keep one Java test account, one `*`-prefixed Bedrock/Floodgate test account, one offline cached account, and one account/UUID not previously joined where required.
6. Record baseline definition/instance/pending/campaign/anomaly/deleted-marker counts before the case.
7. Do not force-load chunks to make a case pass.

---

## ACC-ENV-001 — RC artifact and environment baseline

**Prerequisites:** Clean acceptance server and WP-04 prerelease assets.

**Steps:** Verify jar checksum against the release checksum; record SBOM/dependency manifest; start the server; wait for LoreItems storage initialization; capture plugin/server/Java versions, schema version, configuration, startup logs, database journal mode/foreign-key/integrity evidence, and baseline queue metrics.

**Expected durable/database/physical result:** Database opens successfully in expected schema, integrity passes, service becomes writable, no unexplained pending/review work exists, and no physical lore item is created by startup.

**Evidence:** Hashes, startup log, schema/integrity/journal evidence, configuration fixture/hash, baseline metrics.

**Cleanup:** None.

**Rollback:** If startup/migration is unsafe, stop immediately and restore the pre-test backup.

## ACC-ID-001 — Java identity and administrative surfaces

**Prerequisites:** Java admin account with expected permissions.

**Steps:** Join with the Java account; exercise `/loreitems` and `/loredistribution` top-level/tab-completion/GUI browse surfaces; verify permission-gated commands appear only when granted; perform one harmless browse/audit query.

**Expected:** Correct Java UUID/name is used in audit/display evidence; bounded GUI/query results return; no mutation occurs from browse-only actions.

**Evidence:** Account identity, permission set, command/GUI captures, audit/query evidence.

**Cleanup:** Close GUIs.

**Rollback:** None; a mutation from a browse-only action is a confirmed defect and the server should be restored if durable state changed.

## ACC-ID-002 — Floodgate `*` identity and administrative surfaces

**Prerequisites:** Bedrock/Floodgate test account whose server-visible name begins with `*`.

**Steps:** Join through Bedrock; record actual UUID and visible prefixed name; exercise the same browse/status surfaces as ACC-ID-001; create a later campaign source entry using the exact prefixed spelling for reuse by distribution cases.

**Expected:** Prefix is preserved in display/audit evidence; UUID is authoritative once known; name parsing does not reject the `*`; no Java-name assumption breaks commands or GUIs.

**Evidence:** Floodgate identity evidence, logs, GUI/command results.

**Cleanup:** None.

**Rollback:** None unless durable state was incorrectly mutated.

## ACC-CORE-001 — Create definition from held item

**Prerequisites:** Admin holds a distinctive disposable item with custom components; unique key `acc_create_001`.

**Steps:** Run `/loreitems create acc_create_001 Acceptance Create`; wait for durable result; browse the definition and template; restart the server and browse again.

**Expected:** Exactly one active definition exists with the requested key/display name and held-item template; tracked max stack size is one; definition/audit state survives restart; the source held item is not unexpectedly replaced or duplicated by creation.

**Evidence:** Command result, definition/template query/GUI, relevant database rows/audit, pre/post held slot, restart log.

**Cleanup:** Delete the disposable definition only through the supported confirmed delete path after dependent cases finish.

**Rollback:** Restore backup if definition creation corrupts unrelated state.

## ACC-CORE-002 — Adopt held item

**Prerequisites:** Active disposable definition and an untracked distinctive held item.

**Steps:** Record held-item fingerprint/components; run `/loreitems adopt <lookup-key>`; wait for result; inspect the held item and instance browser; restart and inspect again.

**Expected:** The same physical held item gains exactly one fresh hidden tracked identity for the chosen definition without unintended visible normalization; one instance/audit record exists; restart preserves identity/current state.

**Evidence:** Before/after physical item evidence, instance/audit/current-state evidence, restart evidence.

**Cleanup:** Remove exact disposable instance through supported exact removal.

**Rollback:** If outcome is ambiguous, do not retry adoption; preserve item and recovery evidence and mark the case FAIL.

## ACC-CORE-003 — Give to self and online player

**Prerequisites:** Active definition; admin and second online Java account each with free inventory slot.

**Steps:** Run `/loreitems give <lookup-key>` for self, then `/loreitems give <lookup-key> <online-player>` for second account; wait for delivery; inspect inventories and instance/current-state/audit records.

**Expected:** Each request creates exactly one distinct instance, inserts into a real inventory slot, records delivery/current location, never drops an overflow entity, and reports durable acceptance/completion consistently.

**Evidence:** Commands, physical inventories, instance identities/counts, current-state/audit, delivery metrics.

**Cleanup:** Exact-remove both disposable instances.

**Rollback:** Stop further gives if duplicate or lost work is observed; preserve database and inventories.

## ACC-CORE-004 — Offline direct give, join delivery, and restart

**Prerequisites:** Cached offline account; active definition.

**Steps:** While target is offline, run `/loreitems give <lookup-key> <cached-name-or-uuid>`; verify queued state; restart server while still offline; verify queue persists; join target with free inventory space; wait for delivery; restart again and verify no second copy appears.

**Expected:** Exactly one durable queued delivery survives restart and becomes exactly one physical instance on join; terminal completion survives the second restart.

**Evidence:** Queue rows/status before/after restart, target inventory, audit/current state, delivery metrics.

**Cleanup:** Exact-remove test instance.

**Rollback:** Preserve queued/physical state and restore backup if duplicate delivery occurs.

## ACC-CORE-005 — Full-inventory delivery deferral

**Prerequisites:** Online target with every normal storage slot filled; active definition.

**Steps:** Queue give to target; observe at least one delivery attempt; verify no dropped item/entity; free exactly one valid inventory storage slot; trigger natural wakeup via inventory close/drop/join as applicable; wait for delivery; restart and verify no duplicate.

**Expected:** Request remains pending while full, records full-inventory deferral, creates no overflow drop, then delivers exactly once after space exists.

**Evidence:** Full inventory capture, queue/status transitions, entity observation, metrics, final instance/audit/current state.

**Cleanup:** Remove test instance and restore target inventory.

**Rollback:** Stop worker activity if overflow/drop or duplicate occurs; preserve physical evidence.

## ACC-EDIT-001 — Editor field matrix and preview/cancel

**Prerequisites:** Disposable definition with at least one online instance and permission `enthusia.loreitems.admin.edit`.

**Steps:** Through the supported editor GUI/chat flow, exercise and individually verify: base material; custom/item name; solid color; multi-color gradient; lore line add/edit/remove; lore solid color; lore gradient; enchantment and level; enchantment tooltip visible/hidden; glint override; damage; unbreakable; attribute modifier; item model; maximum stack-size input while confirming tracked output remains one; and other exposed common Paper-supported components. For at least one edit, preview and cancel; for another, confirm. Record revision numbers.

**Expected:** Cancel produces no new committed revision/rollout; each confirmed change creates the intended next revision, preserves unrelated components, and schedules bounded rollout. Invalid input fails validation without partial revision.

**Evidence:** Editor captures, revision history/audit, physical online instance before/after, pending rollout metrics.

**Cleanup:** Leave definition on a known revision for ACC-EDIT-002/003.

**Rollback:** Revert via a new intentional template revision or restore disposable test backup; never hand-edit instance metadata.

## ACC-EDIT-002 — Replace template from held item with uncommon components

**Prerequisites:** Disposable definition and held source item containing at least one component not edited in ACC-EDIT-001.

**Steps:** Record exact held-item components; invoke the advanced replace-template-from-held operation; preview/cancel once and prove no revision; invoke again and confirm; inspect new template and an updated accessible instance.

**Expected:** Confirmed revision reproduces the held template's supported components while preserving LoreItems identity rules and max stack size one; cancel changes nothing.

**Evidence:** Before/after component dump or privileged evidence, revision/audit, instance output.

**Cleanup:** Keep revision for rollout case or intentionally replace with a simple test template.

**Rollback:** New corrective revision or restore disposable data.

## ACC-EDIT-003 — Revision rollout across accessible/inaccessible holders

**Prerequisites:** Same definition represented by instances in: online player inventory, offline player inventory, loaded block container, unloaded block container, nested shulker/bundle sample, dropped entity, item frame/glow frame, and armor stand where supported by current requirements.

**Steps:** Record all instance identities/revisions/locations; confirm a new template revision; observe bounded updates for accessible holders; keep offline/unloaded holders inaccessible long enough to verify pending work; restart during rollout; then naturally join/load/access each deferred holder and observe update completion.

**Expected:** Every instance converges exactly once to the new revision when naturally accessible; no chunk is force-loaded; offline/unloaded work remains durable; restart preserves pending work; identities do not change; no duplicate item is created.

**Evidence:** Revision/pending counts, per-holder before/after evidence, chunk access timing, restart logs, metrics and audit/current-state rows.

**Cleanup:** Return test instances to controlled locations.

**Rollback:** Pause affected work if wrong item/revision is mutated; preserve holder state and restore disposable backup if necessary.

## ACC-TRACK-001 — Player inventory, armor, offhand, cursor, and Ender Chest tracking

**Prerequisites:** Several disposable tracked instances.

**Steps:** Move instances through normal inventory slots, armor, offhand, cursor, and Ender Chest using ordinary player actions; close/reopen inventories and quit/join; inspect current/last-confirmed location after each stable transition.

**Expected:** Current state follows naturally observed moves without scanning/force loading; no identity changes; GUI distinguishes current confirmation from stale/last-confirmed evidence after access is lost.

**Evidence:** Physical slot captures and corresponding current-state/audit output with timestamps.

**Cleanup:** Return instances to inventory.

**Rollback:** None unless tracking mutation damages the item; preserve evidence for mismatch.

## ACC-TRACK-002 — Containers, hopper-observable movement, nested shulker/bundle

**Prerequisites:** Loaded container setup, hopper path, shulker, bundle, and tracked instances; run once with shared-container restriction disabled and once enabled where supported by configuration semantics.

**Steps:** Move items manually into/out of containers; allow a hopper-observable move; place a tracked item into shulker/bundle when allowed; close container and unload chunk naturally; inspect state; reload chunk and reopen; enable restriction for shared nested storage via validated configuration/restart/reload procedure and attempt prohibited insertion.

**Expected:** Observed locations and outer-holder/nested evidence are retained; unloaded location is shown as last-confirmed rather than live; restriction blocks both shulker and bundle placement when enabled without item loss.

**Evidence:** Container/nested physical evidence, config, current-state/audit transitions, logs.

**Cleanup:** Remove items from nested storage and restore default config.

**Rollback:** Restore config and controlled holder state.

## ACC-TRACK-003 — Dropped/display/death/chunk lifecycle tracking

**Prerequisites:** Disposable tracked instances and isolated acceptance area.

**Steps:** Drop an instance; place instances in item frame, glow item frame, and armor stand equipment; perform a controlled player death drop; unload/reload containing chunk naturally; inspect status while loaded and unloaded.

**Expected:** Entity/display/death-drop observations are tracked; unload does not claim live confirmation; re-observation refreshes confirmed state; no force loading occurs.

**Evidence:** Entity IDs/locations where appropriate, physical captures, current-state/audit entries, chunk load timing.

**Cleanup:** Recover test items.

**Rollback:** Preserve entity/chunk state if an item disappears unexpectedly.

## ACC-PROT-001 — Environmental/despawn/durability protection

**Prerequisites:** Disposable tracked items suitable for damage/durability tests.

**Steps:** In isolated controlled tests expose tracked dropped items to fire, lava, explosion, cactus/ordinary item damage, and natural despawn interval; exercise durability loss to the normal break boundary.

**Expected:** Items are not destroyed by protected environmental causes, do not naturally despawn, and do not break from durability exhaustion; identity remains intact. No replacement duplicate is spawned as a protection mechanism.

**Evidence:** Timed physical evidence, event/log evidence where available, unchanged instance identity/current state.

**Cleanup:** Recover items and repair test terrain.

**Rollback:** If destruction occurs, stop the matrix, preserve logs/database, and restore acceptance backup.

## ACC-PROT-002 — Conversion/mob pickup protection and intentional void loss

**Prerequisites:** Disposable tracked items.

**Steps:** Attempt consume/craft/smelt/grind/smith/rename or other identity-losing conversion appropriate to underlying materials; attempt ordinary mob pickup/retention; then intentionally drop a separate tracked item into the void.

**Expected:** Identity-losing conversions and ordinary mob retention are prevented without duplication/loss; intentional void loss is allowed and recorded as terminal destruction, not restored.

**Evidence:** Physical before/after, relevant event/log/audit/current-state evidence including void terminal state.

**Cleanup:** Recover non-void test items.

**Rollback:** Restore acceptance backup if a protected path destroys an item or void loss is incorrectly resurrected.

## ACC-ANOM-001 — Duplicate identity and malformed stack warning/resolution

**Prerequisites:** Controlled test method for producing duplicate hidden identity and malformed stack in acceptance environment; online staff account with audit/review permission.

**Steps:** Introduce two physical copies with same instance UUID and separately a malformed stack; naturally expose both locations; observe immediate staff/console warning and leave conflict unresolved for at least five minutes to observe repeat warning; inspect anomaly GUI/history; resolve intentionally through supported review flow.

**Expected:** Copies/stacks are preserved while unresolved, all observed locations are recorded, warning repeats at configured interval, no automatic winner/deletion/split occurs, and supported resolution produces durable audit evidence.

**Evidence:** Creation method, physical copies, warning timestamps, anomaly/current-state/audit rows, resolution evidence.

**Cleanup:** Remove disposable anomaly items through supported resolution/removal.

**Rollback:** Restore isolated test backup if anomaly tooling mutates unrelated items.

## ACC-ANOM-002 — Ambiguous physical mutation enters review

**Prerequisites:** Acceptance failure-injection/manual harness capable of interrupting a prepared mutation in the documented ambiguous window.

**Steps:** Start one disposable adoption/update/removal/delivery mutation; interrupt after durable preparation/claim at the designated ambiguous point; restart; inspect `/loreitems recovery`, operation target/audit, and physical item; do not retry until evidence is understood; resolve using the supported review action.

**Expected:** Ambiguous state becomes `REVIEW_REQUIRED` or equivalent explicit review gate; system does not blindly repeat physical side effect; operator resolution is evidence-backed and audited.

**Evidence:** Exact interruption point, logs, durable state before/after restart, physical evidence, review action/evidence text.

**Cleanup:** Finish or abort disposable work through supported review flow.

**Rollback:** Restore backup only after preserving ambiguous-state evidence.

## ACC-DEST-001 — Exact instance removal

**Prerequisites:** Disposable definition with at least two instances so wrong-target behavior is observable.

**Steps:** Run `/loreitems remove <definition-uuid> <target-instance-uuid>`; record preview; confirm using `/loreitems confirm-remove <confirmation-token>`; inspect operation/targets; wait for terminal state; restart and re-inspect both target and non-target.

**Expected:** Only exact target physical copy is removed; sibling remains intact; operation/target/audit terminal state is durable and restart-safe.

**Evidence:** Preview, commands, both physical items, operation target rows/status, audit, restart evidence.

**Cleanup:** Keep sibling for subsequent tests.

**Rollback:** Stop destructive worker if wrong target changes; preserve holders and restore backup.

## ACC-DEST-002 — Purge all instances while retaining definition

**Prerequisites:** Disposable definition with instances across accessible and inaccessible holders.

**Steps:** `/loreitems purge <definition-uuid>` then `/loreitems confirm-purge <token>`; inspect target snapshot; pause/resume once; restart while deferred targets remain; naturally access deferred holders until terminal.

**Expected:** Definition/template remains active; every snapshotted/rediscovered instance is eventually physically removed when accessible; no chunk/offline inventory is force-loaded; pause/restart preserve work.

**Evidence:** Definition state, target list/counts, holder evidence, operation metrics/audit, restart logs.

**Cleanup:** Retain empty definition or delete it through ACC-DEST-003.

**Rollback:** Restore disposable backup if wrong definition/target is affected.

## ACC-DEST-003 — Full delete and late-copy/tombstone handling

**Prerequisites:** Disposable definition with one accessible instance and one deliberately hidden late copy in offline/unloaded/backup-simulation holder.

**Steps:** `/loreitems delete <definition-uuid>` then confirm; verify definition disappears from ordinary browse/give/tab-completion; finish known removals; later reintroduce/naturally expose the hidden copy; observe deleted-marker handling and final removal.

**Expected:** Definition stays deleted, minimal tombstone/audit identity remains, late copy is not resurrected as active definition and is removed through normal durable path when accessible.

**Evidence:** Pre-delete definition/instances, ordinary UI absence, tombstone/audit evidence, late-copy observation/removal.

**Cleanup:** Ensure disposable definition has no remaining physical copies.

**Rollback:** Restore pre-case backup if deletion affects another definition or loses tombstone identity prematurely.

## ACC-DEST-004 — Destructive pause/resume and restart phases

**Prerequisites:** Disposable multi-target operation large enough to leave pending targets.

**Steps:** Start a purge/delete; capture operation UUID; `/loreitems pause-operation <operation-uuid>` and verify new claims stop after bounded in-flight work; restart while paused and verify pause persists; resume with `/loreitems resume-operation <operation-uuid>`; repeat a restart with active work; inspect `/loreitems destructive-metrics`, `operations`, and `targets` throughout.

**Expected:** Parent pause is durable; no unbounded new target claims while paused; restart does not mark in-flight work falsely successful; resume continues only safe/naturally accessible work.

**Evidence:** Target state timeline, metrics, logs, physical holder state, audit.

**Cleanup:** Let disposable operation finish.

**Rollback:** Restore backup if pause/restart causes duplicate/wrong-target mutation.

## ACC-DIST-001 — Group validation, preview immutability, duplicate start, marker lifecycle

**Prerequisites:** Active disposable definition and several group fixtures including valid YAML, malformed YAML, unknown key, case-duplicate recipient, oversized/unsafe fixture if practical without resource risk.

**Steps:** `/loredistribution reload`; inspect fixtures; preview valid source; modify source after preview and prove confirm rejects changed fingerprint; restore/re-preview/confirm; attempt to start same source/fingerprint again; inspect active marker and campaign; complete a tiny campaign and verify completed marker.

**Expected:** Invalid sources fail closed with diagnostics; changed preview cannot silently start; durable campaign snapshot is immutable/database-authoritative; duplicate start rejected; active/completed markers reflect durable state.

**Evidence:** Fixture hashes, reload/inspect output, campaign rows/audit, marker filenames/content, duplicate-start result.

**Cleanup:** Archive/remove disposable source after terminal campaign.

**Rollback:** Do not delete campaign rows to retry; restore isolated acceptance backup if durable duplicate campaigns are created.

## ACC-DIST-002 — Java, Bedrock, UUID, and unresolved-first-join recipients

**Prerequisites:** Group source with Java cached name, exact `*BedrockName`, explicit UUID, and a never-seen unresolved name that will later join with matching current name.

**Steps:** Preview/confirm campaign; inspect recipient states; verify known UUID/name binding; leave unresolved recipient pending; later join matching player for first time; inspect durable binding and delivery.

**Expected:** Original display values preserved; case-insensitive name matching works; `*` prefix survives; UUID becomes authoritative once bound; no network lookup is required; unresolved recipient can bind years-later semantics simulated by delayed first join.

**Evidence:** Immutable recipient snapshot, binding audit/state, join logs, final physical deliveries.

**Cleanup:** Remove disposable delivered instances after campaign evidence is complete.

**Rollback:** Preserve campaign snapshot and stop delivery if identity binds to wrong UUID.

## ACC-DIST-003 — Offline/full inventory and exactly-once campaign delivery

**Prerequisites:** Campaign with one offline recipient, one online-full recipient, and one online-free recipient.

**Steps:** Start campaign; observe states; restart while offline/full recipients remain; free slot/join recipients; wait for all delivery; restart again; inspect counts and inventories.

**Expected:** Free recipient gets one item; offline/full recipients remain queued without overflow drop and later each receive exactly one; restart preserves work; terminal campaign completes only after all recipients terminal-delivered.

**Evidence:** Per-state counts over time, queue metrics, inventories/entities, campaign/audit rows, restart evidence.

**Cleanup:** Remove delivered test instances.

**Rollback:** Preserve campaign and physical inventories if duplicate or missing delivery is observed.

## ACC-DIST-004 — Pause, resume, cancel, and restart

**Prerequisites:** Multi-recipient campaign with pending work.

**Steps:** Pause campaign; verify state/counts; restart and prove pause persists/no new deliveries; resume and allow some deliveries; cancel while others remain; restart; inspect delivered versus cancelled recipients and cancelled marker.

**Expected:** Pause/resume durable; cancellation stops future deliveries, preserves already delivered instances, marks remaining recipients cancelled, and produces correct marker/audit; restart never restarts cancelled work.

**Evidence:** Status/recipient pages, physical delivered set, cancelled count, marker, audit and logs.

**Cleanup:** Remove disposable delivered items and retain evidence fixture.

**Rollback:** Restore isolated backup if cancellation deletes delivered items or resumes cancelled work.

## ACC-DIST-005 — Campaign marker repair/reconciliation

**Prerequisites:** Active disposable campaign; backup of marker file.

**Steps:** During controlled acceptance, remove/misplace the active marker without changing SQLite; run `/loredistribution reconcile [page]`; inspect resulting marker and campaign; separately place a changed replacement source with same filename and verify reconciliation does not substitute it for durable snapshot.

**Expected:** Database remains authoritative; reconciliation reconstructs/repairs marker safely; changed replacement source is not treated as the committed campaign; no duplicate campaign/delivery occurs.

**Evidence:** Before/after file hashes/content, campaign snapshot/fingerprint, reconcile output, delivery counts.

**Cleanup:** Restore normal terminal marker arrangement.

**Rollback:** Restore marker backup only after verifying it matches the authoritative campaign UUID/fingerprint.

## ACC-API-001 — Stable Bukkit service outcomes and idempotency

**Prerequisites:** Dedicated test-consumer plugin compiled against the versioned public API and configured to call the Bukkit service directly, never command dispatch.

**Steps:** Request delivery with unique external operation key; replay same key before and after completion; restart and replay again; test unknown definition; malformed/validation request; test service while startup is not writable/degraded; reload/restart plugin lifecycle and call through reacquired service registration as required by contract.

**Expected:** Outcomes distinguish accepted/queued, already accepted/completed, unknown definition, unavailable/read-only, and validation failure; one external key can never create more than one delivery across retry/restart; service registration becomes unavailable when lifecycle requires and returns after healthy initialization.

**Evidence:** Test-consumer source/commit, API binary used, call/result log, database idempotency/delivery evidence, physical inventory.

**Cleanup:** Remove disposable API-delivered instance.

**Rollback:** Stop test consumer and preserve idempotency state if duplicate occurs.

## ACC-LIFE-001 — Configuration reload with active work

**Prerequisites:** Pending delivery, pending template rollout, active destructive operation, and active campaign; known valid alternate reloadable configuration plus invalid configuration fixture.

**Steps:** While work exists, apply valid reload through the installed build's supported configuration reload path; verify atomic new snapshot; then attempt invalid reload; verify old valid snapshot remains; observe active work before/after. Do not use Bukkit `/reload` as a substitute unless the plugin explicitly documents it.

**Expected:** Valid snapshot replaces atomically; invalid candidate changes nothing; active durable work is not discarded; editor sessions close only as documented; service remains consistent.

**Evidence:** Config hashes, reload result/log, queue/operation/campaign state timeline.

**Cleanup:** Restore default acceptance configuration through validated path/restart.

**Rollback:** Clean restart with known-good configuration if reload mechanism becomes unavailable.

## ACC-LIFE-002 — Shutdown under queued load and restart recovery

**Prerequisites:** Non-trivial queued delivery/update/destructive/campaign work and measurable database backlog below configured capacity.

**Steps:** Capture queue states; stop server cleanly while work remains; measure shutdown bounded-drain behavior against configured timeout; restart; inspect pending/recovered/review-required work; let safe work continue.

**Expected:** Intake stops, resources close, shutdown does not hang beyond bounded policy, uncompleted work remains durable, expired ambiguous claims become review rather than blind retry, and no terminal success is fabricated.

**Evidence:** Shutdown/startup logs with timestamps, queue/claim states, database rows, physical inventories/holders, metrics.

**Cleanup:** Complete disposable work.

**Rollback:** Restore backup if pending work disappears or repeats destructive/delivery side effects.

## ACC-OPS-001 — Degraded/read-only startup

**Prerequisites:** Reversible controlled condition that prevents safe writable startup without damaging the only database (for example a copied acceptance database/permission or migration fixture appropriate to the harness).

**Steps:** Start under the controlled failure; attempt create/give/API/campaign mutation only enough to verify rejection; capture service/admin behavior; restore healthy condition and restart.

**Expected:** Plugin fails closed for mutations, reports unavailable/read-only outcome, does not create partial durable/physical work, and returns to healthy read/write after correction.

**Evidence:** Exact induced condition, logs, command/API results, database diff proving no unintended mutation.

**Cleanup:** Restore permissions/fixture and healthy database.

**Rollback:** Replace with verified backup if the induced condition unexpectedly modifies the database.

## ACC-OPS-002 — Offline backup and restore rehearsal

**Prerequisites:** Acceptance dataset containing definitions, instances, pending work, deleted marker, campaign history, and audit records.

**Steps:** Stop server; perform full offline backup of `plugins/EnthusiaLoreItems/`; hash backup/database/jar; restart and make controlled disposable changes; stop; preserve changed state separately; restore full backup and compatible jar; restart; run integrity and compare baseline records; naturally reconcile physical items.

**Expected:** Restored durable dataset exactly matches backup baseline; integrity passes; expected pre-backup pending work resumes; post-backup durable changes disappear as expected; reappearing physical copies are handled as divergence/duplicate/late-copy evidence rather than guessed away.

**Evidence:** Backup manifest/hashes, before/change/restored database comparisons, integrity output, reconciliation logs.

**Cleanup:** Decide whether to remain on restored fixture or return to standard acceptance snapshot.

**Rollback:** The original pre-restore changed directory is the fallback evidence copy.

## ACC-OPS-003 — Release rollback rehearsal

**Prerequisites:** Known-good pre-RC backup and compatible prior artifact/database; RC deployment fixture.

**Steps:** Record pre-RC counts/hashes; deploy RC and allow migration/smoke mutation; stop; preserve RC state; restore pre-RC full backup and compatible jar rather than downgrading the migrated database in place; start and verify integrity/state; run delivery/update/destructive/campaign/API replay safety subset.

**Expected:** Rollback returns exactly to pre-deployment durable state with no mixed-schema use; safety subset passes; RC evidence remains preserved separately.

**Evidence:** Both jar/database hashes, schema versions, backup/restore commands/procedure, state comparisons, safety-subset results.

**Cleanup:** Return server to the designated acceptance RC snapshot after rehearsal.

**Rollback:** If rollback rehearsal fails, stop and preserve both datasets; do not improvise schema edits.

## ACC-OPS-004 — Queue saturation/backpressure and operator metrics

**Prerequisites:** Controlled load generator that can approach configured capacities without risking production data; acceptance-only server.

**Steps:** Generate bounded bursts across direct delivery, update/destructive/campaign/admin-query/reconciliation paths sufficient to reach documented queue/backpressure behavior; record database and worker queue depths/high-water marks, rejections/deferrals, retries, pages, and latency; continue until accepted work drains or reaches deliberate pending state.

**Expected:** No queue exceeds configured capacity, memory structure does not grow unbounded, rejected/deferred work is explicit, no silent work loss/false success occurs, retries/pages remain bounded, and server thread remains free of SQLite/filesystem I/O.

**Evidence:** Load-generator commit/config, metrics time series/raw output, server timings/profile evidence, accepted/completed/pending reconciliation.

**Cleanup:** Drain/cancel disposable work through supported controls.

**Rollback:** Restore acceptance backup if work accounting cannot be reconciled.

## ACC-OPS-005 — 100-player-equivalent staged load

**Prerequisites:** WP-04 profile harness data/config and acceptance load method representing 100 players without violating server/provider constraints.

**Steps:** Run the documented staged load with representative delivery, movement/reconciliation, GUI queries, updates, campaigns, and destructive work; capture server tick/task timings, queue metrics, database latency, throughput, and all accepted-work accounting; perform a restart during residual pending work.

**Expected:** No observed main-thread stall above the WP-04 safety thresholds attributable to LoreItems, no unbounded backlog, no loss/duplication, and restart preserves residual work. Any threshold defined by WP-04 committed profile evidence remains satisfied or the case fails.

**Evidence:** Exact workload/config, player-equivalent method, timings/profile output, p50/p95/p99 metrics where available, queue high-water marks, work reconciliation.

**Cleanup:** Drain disposable workload and restore standard acceptance snapshot.

**Rollback:** Restore acceptance backup if accounting or data integrity fails.

---

## Final regression subset after any confirmed defect

After each WP-05 defect fix, rerun the failed case, every case sharing its state machine/adapter, and at minimum:

- ACC-CORE-004 and ACC-CORE-005 — durable delivery/restart/full inventory;
- ACC-EDIT-003 — revision rollout/restart;
- ACC-ANOM-002 — ambiguous mutation safety when relevant;
- ACC-DEST-001 through ACC-DEST-004 — destructive safety/restart;
- ACC-DIST-003 through ACC-DIST-005 — exactly-once campaign/restart/markers;
- ACC-API-001 — external idempotency;
- ACC-LIFE-001 and ACC-LIFE-002 — reload/shutdown;
- ACC-OPS-002 — restore/data integrity when persistence is affected.

Every confirmed defect requires an automated regression test unless the repository harness cannot instantiate the specific live Paper event/API behavior. Any exception must add a permanent named manual regression case with a technical explanation of the unavailable behavior.

## Final-release gate

The final WP-05 matrix is approved only when **every case above is PASS against the exact final jar SHA**. `BLOCKED`, waived, not-run, screenshot-only, or results from an older jar are not release approval. After the last code change, repeat the full matrix, run the full automated WP-04 suite/profile/package checks, obtain independent code/evidence review and operator sign-off, then perform the documented `v1.0.0` merge/tag/release sequence. No case in this document by itself authorizes starting WP-06.
