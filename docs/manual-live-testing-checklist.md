# EnthusiaLoreItems manual staging/live testing checklist

This is the operator-execution version of `docs/wp-05-manual-acceptance-matrix.md`. It converts each acceptance case into a repeatable, step-by-step checklist for a real Paper/Leaf acceptance server.

It does **not** replace the authoritative WP-05 matrix or `docs/operator-guide.md`. If this guide and the matrix disagree, stop and follow the matrix.

## Current candidate at the time this guide was added

- Source SHA: `7a5ecb3acb72c5a8153ca3b04e2d4a83e7205a47`
- Plugin version: `1.0.1`
- Expected candidate JAR SHA-256: `08bf83f30d20bd86fcb0b6fc118b0c24f087e436019da98c684d83c94f91678b`

Before testing, re-check PR #30. If the frozen candidate SHA or JAR hash changed, use the new exact frozen candidate and update the evidence record. Never continue a release acceptance run on a stale build after a defect fix.

## What counts as PASS

A case is `PASS` only when:

1. every required step was actually executed on the exact candidate;
2. the physical Minecraft result matches the expected result;
3. durable state/log/admin evidence agrees with the physical result;
4. no unrelated item, definition, queue entry, campaign, operation, or player state changed unexpectedly;
5. the required cleanup completed without hiding a failure.

Use `FAIL` for a reproducible incorrect result. Use `BLOCKED` when a required harness/account/server capability is unavailable. Do not turn a blocked case into a pass by skipping the missing part.

## Hard stop conditions

Stop the affected test sequence and preserve evidence immediately if any of these occur:

- duplicate lore-item delivery;
- item loss outside an intentionally destructive test;
- wrong-target removal;
- an ambiguous physical mutation is automatically retried;
- full inventory causes an item to drop on the ground;
- an offline/unloaded holder is force-loaded to make work complete;
- pending work disappears after restart;
- a campaign is recreated from an old source/marker file;
- SQLite integrity fails;
- the plugin reports success when durable state does not match the physical result;
- unrelated definitions/instances are mutated;
- a queue exceeds its configured bound or silently loses accepted work.

Do not retry an ambiguous adoption/update/removal/delivery until the durable state and physical state have been captured.

---

# 1. One-time staging setup

## 1.1 Build a disposable acceptance server

- [ ] Use a non-production Paper/Leaf 1.21.11-compatible server.
- [ ] Use Java 21.
- [ ] Enable the same Geyser/Floodgate stack expected on Enthusia.
- [ ] Copy only the configuration/plugins required to reproduce the live environment; do not use production player data as disposable test data.
- [ ] Create an isolated test area with fire, lava, cactus, TNT/explosion space, hopper/container setup, void access, item frames, glow item frames, armor stands, shulker boxes, bundles, and a chunk that can be unloaded naturally.
- [ ] Confirm you have a clean backup/restore point before destructive testing.

## 1.2 Required test accounts

Keep these accounts available for the whole run:

- [ ] Java admin account with all LoreItems acceptance permissions.
- [ ] Second Java account for online-target tests.
- [ ] Bedrock/Floodgate account whose server-visible name includes the exact `*` prefix.
- [ ] Cached Java account that can remain offline during delivery tests.
- [ ] A never-before-seen account/name for first-join distribution binding.

Record exact UUIDs and displayed names. Preserve the `*` prefix exactly in evidence.

## 1.3 Deploy and hash the exact candidate

1. Stop the staging server.
2. Put the exact candidate JAR in `plugins/`.
3. Record its SHA-256.
   - PowerShell: `Get-FileHash .\plugins\EnthusiaLoreItems.jar -Algorithm SHA256`
   - Linux: `sha256sum plugins/EnthusiaLoreItems.jar`
4. Compare it with the exact frozen-candidate hash.
5. Do not continue if it differs.
6. Record `java -version` and the exact Paper/Leaf build.
7. Start the server and save the complete startup log.
8. Wait until LoreItems storage initialization is complete and the service is writable.

## 1.4 Prepare evidence storage

Create one durable evidence folder per run, for example:

```text
acceptance-evidence/
  2026-09-21-candidate-<short-sha>/
    environment.md
    ACC-ENV-001/
    ACC-ID-001/
    ...
```

For every case record:

- case ID and `PASS`/`FAIL`/`BLOCKED`;
- UTC start/end time;
- candidate source SHA and JAR SHA-256;
- plugin/server/Java versions;
- schema/configuration identity;
- test account names/UUIDs used;
- exact steps performed;
- expected and actual result;
- relevant logs;
- command/GUI/admin status evidence;
- physical item/container/entity evidence;
- relevant queue/operation/campaign counts;
- cleanup performed;
- rollback action if failed.

Screenshots are supplemental evidence only. Preserve logs and durable status/database evidence as well.

## 1.5 Baseline before every destructive block

Before CORE/EDIT/DEST/DIST/LIFE/OPS blocks:

- [ ] Take or verify a restore point.
- [ ] Record active definition count.
- [ ] Record instance count.
- [ ] Record pending/review work counts.
- [ ] Record active destructive operations.
- [ ] Record active campaigns/recipient counts.
- [ ] Record anomaly count.
- [ ] Record deleted-definition-marker count.

Do not hand-edit the SQLite database to make a case pass.

---

# 2. Environment and identity

## ACC-ENV-001 — Candidate artifact and environment baseline

- [ ] **Setup:** clean acceptance server, exact candidate JAR, verified backup.

### Steps

1. Recalculate the candidate JAR SHA-256 and record it.
2. Record Java version and exact Paper/Leaf build.
3. Record Geyser/Floodgate versions.
4. Start the server from a stopped state.
5. Save the complete LoreItems startup section from console/logs.
6. Confirm `plugins/EnthusiaLoreItems/` exists with `config.yml`, `loreitems.db`, `groups/`, `groups/completed/`, and `groups/cancelled/` as applicable.
7. Confirm startup does not report degraded/read-only/unavailable storage.
8. During a controlled offline/read-only database inspection, record schema version and run SQLite integrity evidence such as `PRAGMA quick_check;` and `PRAGMA foreign_key_check;`.
9. Record journal mode and foreign-key enforcement evidence.
10. Record baseline queue/review/campaign/destructive/anomaly counts.
11. Verify startup itself did not create a physical lore item.

### PASS

- [ ] Exact artifact hash matches.
- [ ] Storage is writable.
- [ ] Integrity/foreign-key checks are clean.
- [ ] No unexplained pending/review work exists.
- [ ] No physical item is created by startup.

### Failure action

If startup/migration/integrity is unsafe, stop immediately and restore the pre-test backup.

---

## ACC-ID-001 — Java identity and administrative surfaces

### ACC-ID-001 steps

1. Join with the Java admin account.
2. Record the UUID and exact visible name.
3. Run `/loreitems` and inspect top-level help/tab completion.
4. Run `/loredistribution` and inspect top-level help/tab completion.
5. Open the normal browse GUI/status surfaces.
6. Temporarily test a staff account without one permission and confirm the corresponding destructive/mutating action is not exposed/allowed.
7. Restore the intended permission.
8. Run one browse/audit/status query that must be read-only.
9. Re-check definition/instance/pending counts to prove the browse-only action did not mutate state.

### ACC-ID-001 pass criteria

- [ ] Correct Java UUID/name appears in operator/audit evidence.
- [ ] Permission-gated actions respect permissions.
- [ ] Browse/status surfaces are bounded and read-only.

---

## ACC-ID-002 — Floodgate `*` identity and administrative surfaces

### Steps

1. Join through Bedrock/Floodgate.
2. Record the actual UUID and the exact server-visible `*Name` spelling.
3. Run the same `/loreitems` browse/status checks as ACC-ID-001.
4. Run the same `/loredistribution` inspect/status checks as ACC-ID-001.
5. Confirm the `*` prefix is preserved in displayed/audit identity evidence.
6. Create a disposable distribution group file that contains the exact `*Name`; keep it for ACC-DIST-002.
7. Confirm no Java-only name parser rejects the recipient.

### PASS

- [ ] Prefix preserved.
- [ ] UUID remains authoritative once known.
- [ ] No command/GUI failure caused by the Floodgate name.

---

# 3. Core definition, adoption, and direct delivery

## ACC-CORE-001 — Create definition from held item

### Steps

1. Hold one distinctive disposable item with visible custom components.
2. Capture the held slot/item before the command.
3. Run `/loreitems create acc_create_001 Acceptance Create`.
4. Wait for the durable success response.
5. Browse the definition and record its definition UUID/current revision.
6. Inspect the saved template through the supported GUI/status surface.
7. Confirm tracked output rules show maximum stack size one.
8. Confirm the held source item was not unexpectedly replaced or duplicated by creation.
9. Restart the server cleanly.
10. Browse `acc_create_001` again and verify definition/template/audit state survived.

### PASS

- [ ] Exactly one active definition with key `acc_create_001`.
- [ ] Template matches the source item as supported.
- [ ] Source item was not duplicated/replaced unexpectedly.
- [ ] Definition survives restart.

Keep this definition for later cases; do not delete it yet.

---

## ACC-CORE-002 — Adopt held item

### Steps

1. Hold exactly one untracked disposable item.
2. Record its visible components and slot.
3. Run `/loreitems adopt acc_create_001`.
4. Do not change hotbar slot while the operation completes.
5. Inspect the item after success.
6. Open the instance browser and record the new instance identity privately.
7. Confirm exactly one new instance exists for this adoption.
8. Confirm the visible item was not unintentionally normalized.
9. Restart the server.
10. Re-inspect the same physical item and durable instance/current-state evidence.

### PASS

- [ ] Same physical item now has one tracked identity.
- [ ] Exactly one instance was created.
- [ ] Current location survives restart.
- [ ] No duplicate item exists.

If outcome is ambiguous, do **not** run adopt again. Preserve the item and recovery state and fail the case.

---

## ACC-CORE-003 — Give to self and online player

### Steps

1. Ensure admin and second Java player are online with free normal inventory slots.
2. Record both inventories.
3. Run `/loreitems give acc_create_001` for self.
4. Wait for completion and record the delivered instance.
5. Run `/loreitems give acc_create_001 <online-player>`.
6. Wait for completion and record the second instance.
7. Confirm the two instance IDs are different.
8. Confirm each item is in a real inventory storage slot.
9. Confirm no dropped overflow item entity was spawned.
10. Inspect current-state/audit/delivery status.

### PASS

- [ ] One and only one item delivered per request.
- [ ] Two distinct instances.
- [ ] Durable location agrees with physical slot.
- [ ] No overflow drop.

---

## ACC-CORE-004 — Offline give, restart, join delivery, restart again

### Steps

1. Use a cached account and keep it offline.
2. Record its known UUID/name.
3. Run `/loreitems give acc_create_001 <cached-name-or-uuid>`.
4. Inspect recovery/status and verify the delivery is queued, not completed.
5. Restart the server while the target remains offline.
6. Verify the same queued delivery still exists after restart.
7. Join the target with at least one free normal inventory slot.
8. Wait for the natural wake/delivery.
9. Record the exact delivered item and durable completion.
10. Restart again.
11. Rejoin/check inventory and verify no second copy appears.

### PASS

- [ ] One durable queued delivery survives the first restart.
- [ ] Exactly one physical item is inserted on join.
- [ ] Completion survives the second restart.
- [ ] No duplicate exists.

---

## ACC-CORE-005 — Full-inventory delivery deferral

### Steps

1. Fill every normal storage slot of the target player with ordinary items.
2. Capture the full inventory.
3. Queue `/loreitems give acc_create_001 <target>`.
4. Wait through at least one delivery attempt/wakeup.
5. Confirm the lore item is **not** on the ground and is not in an invalid slot.
6. Inspect delivery state and confirm full-inventory deferral/pending state.
7. Free exactly one valid storage slot.
8. Trigger a natural wakeup such as inventory close, drop event, or rejoin.
9. Wait for delivery.
10. Record final slot/current-state/audit evidence.
11. Restart and verify no duplicate.

### PASS

- [ ] No overflow drop while full.
- [ ] Pending state remains durable.
- [ ] Exactly one item arrives after space exists.
- [ ] Restart does not redeliver.

---

# 4. Template editor and rollout

## ACC-EDIT-001 — Editor field matrix, preview, cancel, validation

Use the installed build's supported editor GUI/chat flow and tab completion for exact actions.

### Steps

1. Open the disposable definition in the editor.
2. Record current revision.
3. For each supported field below, make one disposable edit and preview it:
   - base material;
   - custom/item name;
   - solid color;
   - multi-color gradient;
   - lore add/edit/remove;
   - lore solid color;
   - lore gradient;
   - enchantment and level;
   - enchant tooltip visible/hidden;
   - glint override;
   - damage;
   - unbreakable;
   - attribute modifier;
   - item model;
   - maximum stack-size input;
   - any other common Paper-supported component exposed by the installed editor.
4. For at least one edit, choose **cancel**.
5. Verify cancel created no new revision and no rollout work.
6. For another edit, choose **confirm**.
7. Verify revision increments exactly once.
8. Verify an accessible instance updates while preserving unrelated components and identity.
9. Enter at least one invalid value.
10. Confirm validation rejects it without partial revision.
11. Verify tracked output remains maximum stack size one even if a larger stack-size component was entered.

### PASS

- [ ] Cancel is a true no-op.
- [ ] Confirm creates exactly one intended revision.
- [ ] Invalid input creates no revision.
- [ ] Unrelated components/identity are preserved.

---

## ACC-EDIT-002 — Replace template from held item

### Steps

1. Create/hold a source item containing at least one uncommon supported component not exercised above.
2. Record the exact visible/component state.
3. Open advanced replace-template-from-held.
4. Preview the replacement and cancel it.
5. Verify no new revision exists.
6. Repeat the operation and confirm it.
7. Verify exactly one new revision exists.
8. Inspect the saved template.
9. Inspect one accessible tracked instance after rollout.
10. Confirm LoreItems hidden identity rules and max stack size one are preserved.

### PASS

- [ ] Cancel changes nothing.
- [ ] Confirm reproduces supported source components.
- [ ] Identity/max-stack rules remain intact.

---

## ACC-EDIT-003 — Rollout across accessible and inaccessible holders

### Setup

Place separate instances of the same definition in:

- [ ] online player inventory;
- [ ] offline player inventory;
- [ ] loaded block container;
- [ ] unloaded block container;
- [ ] nested shulker or bundle;
- [ ] dropped item entity;
- [ ] item frame/glow item frame;
- [ ] armor stand where supported.

### Steps

1. Record every instance ID, current revision, and location before editing.
2. Confirm a new template revision.
3. Observe accessible holders update in bounded fashion.
4. Keep the offline account offline and the chosen chunk unloaded.
5. Verify those inaccessible instances remain pending rather than being force-loaded.
6. Restart while pending rollout work exists.
7. Verify pending work survives restart.
8. Join the offline player naturally.
9. Load/open the unloaded container naturally.
10. Access the nested holder naturally.
11. Re-observe entities/displays.
12. Confirm every instance converges exactly once to the new revision.
13. Confirm instance IDs never change and no duplicate is created.

### PASS

- [ ] Accessible holders update.
- [ ] Inaccessible holders stay durable/pending.
- [ ] No force loading.
- [ ] Restart preserves work.
- [ ] Every natural access converges exactly once.

---

# 5. Tracking

## ACC-TRACK-001 — Player inventory, armor, offhand, cursor, Ender Chest

### Steps

1. Use several disposable tracked items.
2. Move one through ordinary inventory slots.
3. Equip one in armor where material permits.
4. Move one to offhand.
5. Hold one on the inventory cursor briefly.
6. Put one in Ender Chest.
7. Close/reopen relevant inventories.
8. Quit and rejoin.
9. After each stable transition, inspect current/last-confirmed status.
10. Record timestamps and physical slots.

### PASS

- [ ] Current state follows naturally observed moves.
- [ ] Identity never changes.
- [ ] Inaccessible state is shown as stale/last-confirmed, not falsely live.

---

## ACC-TRACK-002 — Containers, hopper, nested shulker/bundle, restriction policy

### Steps

1. With `shared-containers-allowed: true`, move a tracked item into/out of a loaded chest/container.
2. Allow a normal hopper-observable movement.
3. Put a tracked item into a shulker/bundle where allowed.
4. Close the holder and unload the chunk naturally.
5. Inspect status while unloaded.
6. Reload the chunk naturally and reopen the holder.
7. Verify location refreshes.
8. Change `shared-containers-allowed` to `false` through the documented validated reload/restart path.
9. Attempt to insert a tracked item into both a shulker and a bundle.
10. Confirm insertion is blocked without loss.
11. Confirm removing an existing tracked item remains possible as documented.
12. Restore default configuration through the validated path.

### PASS

- [ ] Container/hopper/nested locations are tracked.
- [ ] Unloaded locations are not falsely reported live.
- [ ] Restriction blocks insertion into shulker and bundle without deleting the item.

---

## ACC-TRACK-003 — Dropped/display/death/chunk lifecycle

### Steps

1. Drop a tracked instance in the isolated area.
2. Place another in an item frame.
3. Place another in a glow item frame.
4. Equip one on an armor stand where supported.
5. Perform a controlled player death with a disposable tracked item that should drop normally.
6. Record entity/display locations while loaded.
7. Leave the area so the containing chunk unloads naturally.
8. Inspect status while unloaded.
9. Return/load the chunk naturally.
10. Re-inspect status and recover the items.

### PASS

- [ ] Entity/display/death-drop observations are recorded.
- [ ] Unload removes live-confirmation claim without inventing a new location.
- [ ] Re-observation refreshes confirmation.
- [ ] No force loading occurs.

---

# 6. Protection and terminal void

## ACC-PROT-001 — Environmental, despawn, and durability protection

Run each environmental exposure as its own mini-test so failures are attributable.

### Steps

1. Drop a disposable tracked item into/near fire and observe.
2. Recover it and verify identity.
3. Repeat with lava.
4. Repeat with an isolated explosion/TNT test.
5. Repeat with cactus/ordinary item damage.
6. Leave a separate dropped tracked item through the normal despawn interval.
7. Exercise a tracked durable item to the normal break boundary.
8. After each mini-test, verify the same tracked identity still exists.
9. Confirm protection did not create a replacement duplicate.

### PASS

- [ ] Protected causes do not destroy the tracked item.
- [ ] Natural despawn is prevented.
- [ ] Durability exhaustion does not destroy it.
- [ ] Identity remains the same and no duplicate replacement appears.

Any unexpected destruction is a hard stop.

---

## ACC-PROT-002 — Conversion/mob pickup protection and intentional void loss

### Steps

1. With disposable tracked items, attempt applicable identity-losing paths: consume, craft, smelt, grind, smith, rename, or other conversion relevant to the material.
2. Verify each prohibited conversion leaves the tracked item intact and does not duplicate it.
3. Attempt normal mob pickup/retention where applicable.
4. Verify the tracked item is not silently lost into mob state.
5. For the void portion, use a separate disposable tracked item.
6. Record its instance identity and current location.
7. Drop it below the world's minimum height/into the void.
8. Wait for the terminal-void workflow to settle.
9. Confirm the physical entity is gone.
10. Confirm durable instance state is terminal void-destroyed with matching audit/current-state evidence.
11. Restart the server.
12. Verify the item is not resurrected or re-delivered.

### PASS

- [ ] Conversion/mob-retention protections preserve items.
- [ ] Intentional void loss is allowed and durably terminal.
- [ ] Void item is not restored after restart.

---

# 7. Anomaly and ambiguous-mutation safety

## ACC-ANOM-001 — Duplicate identity and malformed stack

Use only the repository's controlled acceptance method/harness for creating duplicate hidden identity and malformed-stack fixtures. Do **not** improvise by editing production database rows or stripping hidden identity metadata.

### Steps

1. Create two disposable physical copies with the same instance UUID using the controlled acceptance method.
2. Place them in two naturally observable locations.
3. Observe staff/console warning.
4. Keep the conflict unresolved for at least five minutes (or the configured duplicate warning interval) and record the repeated warning.
5. Open anomaly/history GUI/status.
6. Confirm both physical copies remain present while unresolved.
7. Create/expose the malformed-stack fixture separately.
8. Confirm it is preserved and flagged rather than silently split/deleted.
9. Resolve the anomaly using the supported staff review flow.
10. Re-observe the locations.
11. Confirm durable audit evidence records the resolution.

### PASS

- [ ] No automatic winner/deletion/split.
- [ ] All observed locations/evidence retained.
- [ ] Warning repeats at configured interval.
- [ ] Staff resolution is explicit and audited.

If the controlled fixture method is unavailable, mark this case `BLOCKED`; do not invent a destructive shortcut.

---

## ACC-ANOM-002 — Ambiguous physical mutation enters review

### Steps

1. Use the acceptance failure-injection/manual harness for one disposable adoption, update, removal, or delivery.
2. Record the mutation/operation identity.
3. Allow durable preparation/claim to commit.
4. Interrupt at the designated ambiguous window after preparation but before durable completion can be proven.
5. Restart the server.
6. Inspect `/loreitems recovery`, operation target/status, audit evidence, and the physical item.
7. Verify the system does **not** automatically repeat the physical effect.
8. Confirm state is `REVIEW_REQUIRED` or the documented equivalent explicit review gate.
9. Determine the actual physical result from evidence.
10. Resolve using the supported privileged review action with evidence text.
11. Confirm resolution is audited.

### PASS

- [ ] Ambiguity is review-gated.
- [ ] No blind retry.
- [ ] Resolution requires explicit evidence.

---

# 8. Destructive administration

## ACC-DEST-001 — Exact instance removal

### Steps

1. Create/retain at least two instances of the same disposable definition.
2. Record both instance UUIDs and physical locations.
3. Run `/loreitems remove <definition-uuid> <target-instance-uuid>`.
4. Record the preview and confirmation token.
5. Run `/loreitems confirm-remove <confirmation-token>`.
6. Inspect `/loreitems operations` and `/loreitems targets <operation-uuid>`.
7. Naturally expose the target until the operation becomes terminal.
8. Verify the target physical copy is gone.
9. Verify the sibling copy is unchanged.
10. Restart the server and inspect both again.

### PASS

- [ ] Only the exact target is removed.
- [ ] Sibling survives unchanged.
- [ ] Operation/target/audit state is terminal and restart-safe.

---

## ACC-DEST-002 — Purge all instances while retaining definition

### Steps

1. Create instances across accessible and inaccessible holders.
2. Record all instance IDs/locations.
3. Run `/loreitems purge <definition-uuid>`.
4. Record preview/target counts.
5. Confirm with `/loreitems confirm-purge <token>`.
6. Inspect operation/targets.
7. Pause once with `/loreitems pause-operation <operation-uuid>`.
8. Confirm no new target claims begin after bounded in-flight work settles.
9. Restart while paused; confirm pause persists.
10. Resume with `/loreitems resume-operation <operation-uuid>`.
11. Keep offline/unloaded targets inaccessible and verify they remain pending.
12. Naturally join/load holders one at a time.
13. Verify each copy is physically removed exactly once.
14. Confirm the definition/template remains active and usable.

### PASS

- [ ] Definition retained.
- [ ] All targeted/rediscovered instances eventually removed when naturally accessible.
- [ ] No force loading.
- [ ] Pause/restart/resume are durable.

---

## ACC-DEST-003 — Full delete and late-copy/tombstone handling

### Steps

1. Create a disposable definition with one known accessible instance and one deliberately hidden late copy in an offline/unloaded/controlled backup-simulation holder.
2. Record definition/instance identities.
3. Run `/loreitems delete <definition-uuid>`.
4. Record preview and confirm using `/loreitems confirm-delete <token>`.
5. Confirm the definition disappears from normal browse/give/tab-completion surfaces.
6. Allow known accessible removals to complete.
7. Confirm deleted-marker/tombstone identity remains in durable evidence.
8. Later expose the hidden copy naturally.
9. Verify it is associated with the deleted definition marker rather than recreating an active definition.
10. Let normal durable removal complete.
11. Confirm the definition remains deleted after the late copy is gone.

### PASS

- [ ] Definition stays hidden/deleted.
- [ ] Tombstone/audit identity remains sufficient to handle late copies.
- [ ] Late copy is removed through normal durable processing.
- [ ] No active definition is resurrected.

---

## ACC-DEST-004 — Pause/resume and restart phases

### Steps

1. Start a multi-target purge/delete large enough to leave pending work.
2. Record operation UUID and initial target-state counts.
3. Run `/loreitems pause-operation <operation-uuid>`.
4. Observe target counts until bounded already-in-flight work settles.
5. Confirm new claims stop while paused.
6. Restart the server while paused.
7. Confirm the operation remains paused.
8. Run `/loreitems destructive-metrics`, `/loreitems operations`, and `/loreitems targets <operation-uuid>`.
9. Resume the operation.
10. Allow some work to progress.
11. Restart again while active work remains.
12. Confirm no in-flight target is falsely marked successful merely because of restart.
13. Naturally expose remaining holders and let safe work finish.

### PASS

- [ ] Pause persists across restart.
- [ ] No unbounded claims while paused.
- [ ] Restart preserves/reviews ambiguous work rather than fabricating success.

---

# 9. One-use mass distributions

Group files belong directly in `plugins/EnthusiaLoreItems/groups/` and use exactly:

```yaml
display-name: Acceptance campaign
players:
  - JavaPlayer
  - '*BedrockPlayer'
  - 01234567-89ab-cdef-0123-456789abcdef
```

Supported top-level keys are exactly `display-name` and `players`.

## ACC-DIST-001 — Validation, preview immutability, duplicate start, markers

### Steps

1. Create separate fixtures for:
   - valid YAML;
   - malformed YAML;
   - unknown top-level key;
   - case-duplicate recipient;
   - an unsafe/oversized fixture only if it can be tested safely within provider/resource limits.
2. Run `/loredistribution reload`.
3. Confirm invalid fixtures report diagnostics and do not become partial campaigns.
4. Run `/loredistribution inspect <valid-group.yml>`.
5. Run `/loredistribution preview <valid-group.yml> acc_create_001`.
6. Record campaign UUID/source fingerprint from preview.
7. Modify the source file after preview.
8. Run `/loredistribution confirm <campaign-uuid>` and verify confirmation is rejected because the fingerprint changed.
9. Restore the intended source and preview again.
10. Confirm the new preview.
11. Inspect `/loredistribution status <campaign-uuid>` and active marker state.
12. Attempt to start the same source/fingerprint again.
13. Verify duplicate start is rejected.
14. Let the tiny campaign complete.
15. Verify the completed marker and durable campaign agree.

### PASS

- [ ] Invalid sources fail closed.
- [ ] Changed preview cannot silently start.
- [ ] Durable snapshot is immutable.
- [ ] Duplicate source/fingerprint cannot create a second campaign.
- [ ] Active/completed markers mirror durable state.

---

## ACC-DIST-002 — Java, Bedrock, UUID, unresolved first-join binding

### Steps

1. Create one group with:
   - a cached Java name;
   - the exact `*BedrockName`;
   - an explicit UUID;
   - a never-seen name that will later join for the first time.
2. Preview and confirm the campaign.
3. Run `/loredistribution recipients <campaign-uuid> all`.
4. Record original recipient values and known UUID bindings.
5. Confirm the unknown recipient remains unresolved rather than causing a network lookup/start failure.
6. Join the never-before-seen matching player later.
7. Inspect recipient state again.
8. Verify its UUID is bound exactly once.
9. Wait for normal delivery.
10. Confirm case-insensitive name matching and `*` prefix preservation.

### PASS

- [ ] Original strings preserved.
- [ ] Cached/UUID identities bind correctly.
- [ ] Unresolved name can bind on later first join.
- [ ] No wrong-UUID binding.

---

## ACC-DIST-003 — Offline/full inventory/exactly-once campaign delivery

### Steps

1. Prepare a campaign with one offline recipient, one online player with full storage, and one online player with free space.
2. Start the campaign.
3. Record per-state recipient counts.
4. Confirm free recipient receives exactly one item.
5. Confirm full recipient gets no ground drop and remains queued-full.
6. Confirm offline recipient remains queued-offline.
7. Restart while both blocked recipients remain blocked.
8. Verify states survive restart.
9. Free one slot for the full player and trigger a natural wake.
10. Join the offline recipient.
11. Wait for both deliveries.
12. Confirm exactly one physical instance per recipient.
13. Restart again.
14. Verify campaign remains terminal and no duplicate delivery occurs.

### PASS

- [ ] Exactly-once delivery to all recipients.
- [ ] No overflow drop.
- [ ] Restart-safe blocked states.
- [ ] Campaign completes only after recipients are terminal-delivered.

---

## ACC-DIST-004 — Pause, resume, cancel, restart

### Steps

1. Start a multi-recipient campaign with pending work.
2. Run `/loredistribution pause <campaign-uuid>`.
3. Record status/counts.
4. Restart and verify pause persists with no new delivery.
5. Run `/loredistribution resume <campaign-uuid>`.
6. Allow some recipients to become delivered.
7. Run `/loredistribution cancel <campaign-uuid>` while others remain pending.
8. Record delivered versus cancelled recipient counts.
9. Confirm already-delivered physical items remain present.
10. Confirm pending recipients become cancelled and receive no future item.
11. Restart again.
12. Verify cancelled work does not restart.
13. Verify cancelled marker/audit state.

### PASS

- [ ] Pause/resume/cancel durable.
- [ ] Cancellation preserves already-delivered items.
- [ ] Remaining recipients are cancelled.
- [ ] Restart never resumes cancelled work.

---

## ACC-DIST-005 — Campaign marker repair/reconciliation

### Steps

1. Start a disposable active campaign.
2. Back up its active marker file and record the hash.
3. Remove/misplace the active marker **without changing SQLite**.
4. Run `/loredistribution reconcile [page]`.
5. Inspect the repaired marker and `/loredistribution status <campaign-uuid>`.
6. Verify the marker is reconstructed from durable campaign identity/fingerprint.
7. Separately place a changed replacement source using the same original filename.
8. Run reconciliation again.
9. Verify the changed file is not substituted for the committed campaign snapshot.
10. Confirm no duplicate campaign/delivery occurs.

### PASS

- [ ] Database remains authoritative.
- [ ] Marker repair reflects durable campaign identity.
- [ ] Changed replacement source is not trusted as committed state.

---

# 10. Stable Bukkit API

## ACC-API-001 — Service outcomes and external idempotency

Use a dedicated test-consumer plugin compiled against the versioned public API. Do not test this by dispatching commands.

### Steps

1. Install the test consumer on the acceptance server.
2. Reacquire the LoreItems Bukkit service according to its lifecycle contract.
3. Submit a valid delivery request with a unique external operation key.
4. Record the returned status and durable delivery identity.
5. Replay the exact same operation key before physical completion.
6. Verify no second delivery is created.
7. After completion, replay the same key again.
8. Restart the server and replay again.
9. Verify all replays refer to the same accepted operation/delivery.
10. Submit an unknown definition.
11. Submit a malformed/validation-invalid request.
12. Exercise service access while startup is intentionally not writable/degraded in ACC-OPS-001.
13. Reload/restart and verify consumers reacquire service registration as documented.

### PASS

- [ ] Outcomes distinguish accepted/queued, replay/already accepted or completed, unknown definition, validation failure, and unavailable/read-only.
- [ ] One external operation key can never create more than one delivery across retries/restarts.

---

# 11. Lifecycle

## ACC-LIFE-001 — Configuration reload with active work

### Steps

1. Create at least one pending direct delivery.
2. Leave one pending template rollout.
3. Leave one active destructive operation.
4. Leave one active distribution campaign.
5. Record all states/counts.
6. Change one reloadable setting to a valid alternate value.
7. Use the installed build's documented configuration reload path; do **not** use Bukkit `/reload` as a substitute.
8. Verify the complete new validated snapshot became active atomically.
9. Verify all existing durable work still exists.
10. Prepare an invalid configuration candidate.
11. Attempt reload.
12. Verify reload fails and the previous valid snapshot remains active.
13. Verify active delivery/update/destructive/campaign work still exists and continues safely.
14. Restore default acceptance configuration through the validated path/restart.

### PASS

- [ ] Valid reload atomic.
- [ ] Invalid reload is a complete no-op.
- [ ] Durable work is not discarded or duplicated.

---

## ACC-LIFE-002 — Shutdown under queued load and restart recovery

### Steps

1. Create non-trivial pending delivery/update/destructive/campaign work below configured queue capacity.
2. Record queue/claim/operation/campaign states.
3. Stop the server cleanly while work remains.
4. Record shutdown start/end timestamps.
5. Confirm shutdown remains within the configured bounded-drain policy.
6. Start the server again.
7. Inspect all prior work.
8. Verify safe pending work resumes.
9. Verify expired/ambiguous claims become review rather than blind retry.
10. Verify no terminal success was fabricated for work that did not complete physically.
11. Naturally expose holders/players and let safe work continue.

### PASS

- [ ] Bounded shutdown.
- [ ] Pending work survives.
- [ ] Ambiguous claims become review.
- [ ] No duplicate side effects or false success.

---

# 12. Operations, backup, rollback, and load

## ACC-OPS-001 — Degraded/read-only startup

Run this only on a disposable acceptance copy.

### Steps

1. Stop the server and take a verified backup.
2. Create a reversible condition that prevents safe writable startup, such as a copied acceptance database/plugin-data permission fixture appropriate to the staging host.
3. Start the server.
4. Save the complete startup log.
5. Confirm LoreItems reports unavailable/degraded/read-only behavior rather than writable success.
6. Attempt only minimal create/give/API/campaign mutations needed to prove rejection.
7. Verify no physical item is created and no partial durable work appears.
8. Stop the server.
9. Restore normal permission/fixture state.
10. Start again and confirm healthy read/write initialization.

### PASS

- [ ] Mutations fail closed while degraded.
- [ ] No partial durable/physical mutation.
- [ ] Healthy writable state returns after the controlled fault is removed.

---

## ACC-OPS-002 — Offline backup and restore rehearsal

### Steps

1. Build an acceptance dataset containing definitions, instances, pending work, a deleted marker, campaign history, and audit records.
2. Record baseline counts/identities.
3. Stop the server completely.
4. Copy the **entire** `plugins/EnthusiaLoreItems/` directory.
5. Hash the backup, `loreitems.db`, and deployed JAR.
6. Start the server and make controlled disposable changes.
7. Stop again.
8. Preserve this changed state separately as evidence.
9. Restore the complete original plugin data directory and compatible JAR.
10. Start the server.
11. Run integrity/schema checks.
12. Compare definitions, instance identities/counts, pending work, deleted markers, campaigns, and audit tail against baseline.
13. Naturally reconcile physical test items.

### PASS

- [ ] Durable dataset matches backup baseline.
- [ ] Integrity passes.
- [ ] Pre-backup pending work resumes as expected.
- [ ] Post-backup durable changes are absent.
- [ ] Physical divergence surfaces as duplicate/late-copy evidence instead of being guessed away.

---

## ACC-OPS-003 — Release rollback rehearsal

The documented rollback for 1.0.1 is a **full restore to the compatible v1.0.0 backup/JAR pair**, not running v1.0.0 against a migrated 1.0.1 database.

### Steps

1. Begin from a known-good pre-1.0.1/v1.0.0 backup and its exact compatible v1.0.0 JAR.
2. Record pre-upgrade counts, DB hash, JAR hash, and schema version.
3. Deploy the candidate 1.0.1 JAR and start the server.
4. Allow migrations/startup to complete.
5. Perform one controlled smoke mutation.
6. Stop the server.
7. Preserve the entire 1.0.1 state separately.
8. Restore the full pre-upgrade plugin-data backup.
9. Restore the exact compatible v1.0.0 JAR.
10. Start the server.
11. Verify integrity/schema/counts match the pre-upgrade state.
12. Run a small safety subset: delivery replay, update, destructive action, distribution behavior, and API replay as applicable to that prior release.
13. Stop and preserve evidence.
14. Return the staging server to the designated current-candidate snapshot before continuing current acceptance.

### PASS

- [ ] Rollback exactly restores the prior compatible state.
- [ ] No mixed-schema jar/database pairing.
- [ ] Safety subset passes.

---

## ACC-OPS-004 — Queue saturation/backpressure and metrics

### Steps

1. Use an acceptance-only controlled load generator.
2. Record configured capacities and per-tick mutation budget.
3. Generate bounded bursts of direct delivery work.
4. Add update/destructive/campaign/admin-query/reconciliation load in controlled phases.
5. Record database queue depth/high-water mark, worker queues, deferrals/rejections, retry counts, pages, and latency.
6. Verify the server thread remains free of SQLite/filesystem work and remains responsive.
7. Continue until accepted work drains or reaches an intentional durable pending state.
8. Reconcile accepted versus completed/pending/review work exactly.
9. Drain/cancel disposable work using supported controls.

### PASS

- [ ] No queue exceeds configured capacity.
- [ ] No unbounded in-memory growth.
- [ ] Rejection/deferral is explicit.
- [ ] No silent work loss/false success.
- [ ] Accepted work reconciles exactly.

---

## ACC-OPS-005 — 100-player-equivalent staged load

### Steps

1. Use the committed WP-04 profile configuration as the workload reference.
2. Choose the acceptance load method that represents 100 players without violating host/provider limits.
3. Record exact generator/configuration/version.
4. Run representative delivery, movement/reconciliation, GUI queries, template updates, campaigns, and destructive work.
5. Capture tick/task timings throughout.
6. Capture DB latency, throughput, queue high-water marks, pending/review counts, and accepted-work accounting.
7. Restart while residual pending work remains.
8. Re-record queue/work state after restart.
9. Let safe residual work settle.
10. Reconcile every accepted operation to completed, pending, cancelled, or review state.

### PASS

- [ ] No LoreItems-attributable main-thread stall exceeds committed WP-04 safety thresholds.
- [ ] No unbounded backlog.
- [ ] No loss/duplication.
- [ ] Restart preserves residual work.
- [ ] All accepted work reconciles.

---

# 13. Final regression rules

After **any confirmed defect fix**, do not merely rerun the single failed case.

Rerun:

1. the failed case;
2. every case using the same state machine/adapter;
3. at minimum, when relevant:
   - [ ] ACC-CORE-004
   - [ ] ACC-CORE-005
   - [ ] ACC-EDIT-003
   - [ ] ACC-ANOM-002
   - [ ] ACC-DEST-001
   - [ ] ACC-DEST-002
   - [ ] ACC-DEST-003
   - [ ] ACC-DEST-004
   - [ ] ACC-DIST-003
   - [ ] ACC-DIST-004
   - [ ] ACC-DIST-005
   - [ ] ACC-API-001
   - [ ] ACC-LIFE-001
   - [ ] ACC-LIFE-002
   - [ ] ACC-OPS-002 when persistence is affected.

All reruns must use the **new exact candidate JAR** after the final defect fix.

---

# 14. Final go/no-go checklist

Do not recommend production deployment until all applicable items below are true.

- [ ] Exact final JAR SHA matches the frozen release candidate.
- [ ] Java 21 / intended Paper/Leaf build verified.
- [ ] Startup/integrity baseline passed.
- [ ] Java identity passed.
- [ ] Bedrock/Floodgate identity passed.
- [ ] Core create/adopt/give/offline/full-inventory delivery passed.
- [ ] Editor and rollout matrix passed.
- [ ] Tracking matrix passed.
- [ ] Protection and terminal-void tests passed.
- [ ] Duplicate/malformed/ambiguous mutation safety passed.
- [ ] Exact removal/purge/delete/pause-restart destructive tests passed.
- [ ] Distribution validation/identity/exactly-once/control/reconcile tests passed.
- [ ] Public Bukkit API idempotency/lifecycle test passed.
- [ ] Config reload and queued-load shutdown/restart tests passed.
- [ ] Degraded-mode test passed.
- [ ] Offline backup/restore rehearsal passed.
- [ ] Release rollback rehearsal passed.
- [ ] Queue/backpressure test passed.
- [ ] 100-player-equivalent staged load passed.
- [ ] Every `FAIL` has a linked fix and required regression rerun on the new exact candidate.
- [ ] Every `BLOCKED` case is resolved or explicitly accepted as a release blocker; no blocked case is silently counted as pass.
- [ ] Deep-audit protocol on PR #30 reports `CLEAN_STREAK: 2/2` and `READY FOR LIVE DEPLOYMENT — TWO CONSECUTIVE CLEAN DEEP REVIEWS`.
- [ ] Separate owner authorization has been given before production deployment.

## Production deployment rule

Passing this manual checklist does not override the PR #30 deep-audit protocol. The live server remains blocked until the frozen candidate has two consecutive clean deep-review rounds, final exact-head gates remain green, and the owner explicitly authorizes deployment.
