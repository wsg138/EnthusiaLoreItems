# Destructive administration

Destructive lore-item actions are durable operations. They are not immediate metadata edits and they must not be treated as successful until their targets reach terminal verified states.

## Permissions

- `enthusia.loreitems.admin.remove`: preview and confirm exact-instance removal.
- `enthusia.loreitems.admin.purge`: preview and confirm removal of every instance while retaining the definition.
- `enthusia.loreitems.admin.delete`: preview and confirm full definition deletion plus removal of every instance.
- `enthusia.loreitems.admin.destructive.inspect`: inspect operations, targets, and queue metrics.
- `enthusia.loreitems.admin.destructive.control`: pause or resume a destructive parent operation.
- `enthusia.loreitems.admin.destructive.review`: resolve targets that require evidence review.

## Starting an operation

### Exact instance removal

Use either the instance browser right-click action or:

```text
/loreitems remove <definition-uuid> <instance-uuid>
```

The preview is valid for five minutes and is tied to the latest destructive preview made by that actor. Start it only with the operation-specific command printed by the preview:

```text
/loreitems confirm-remove <confirmation-token>
```

### Purge a definition's instances

Use the **Purge every instance** button in definition management or:

```text
/loreitems purge <definition-uuid>
/loreitems confirm-purge <confirmation-token>
```

A purge keeps the definition and template active. It physically removes every snapshotted instance, and later rediscovered copies remain scheduled for removal.

### Delete a definition and its instances

Use the **Delete definition and items** button in definition management or:

```text
/loreitems delete <definition-uuid>
/loreitems confirm-delete <confirmation-token>
```

Full deletion marks the definition deleted before physical execution begins. The definition disappears from ordinary creation, give, and management interfaces. Known and later rediscovered copies remain scheduled for physical removal; deletion does not merely stop tracking them.

## Confirmation safety

Each preview shows the definition, revision, target count, inaccessible count, queued count, anomaly count, and irreversible result. Confirmation sessions:

- are operation-specific;
- accept only the latest preview for the actor;
- expire after five minutes;
- are consumed once;
- are cleared by plugin reload and shutdown;
- cannot be reused for a different remove, purge, or delete action.

The durable operation and its fixed targets are committed before any physical item mutation is attempted.

## Inspecting work

```text
/loreitems operations [page]
/loreitems targets <operation-uuid> [page]
/loreitems destructive-metrics
```

Operation output includes the parent target, expected revision, actor, state, target counts, queue age, and timestamps. Target output includes expected identity and location evidence, attempts, lease status, before/after fingerprints, last error, and age.

The existing command below continues to page delivery and template-update recovery work:

```text
/loreitems recovery [page]
```

## Pausing and resuming

```text
/loreitems pause-operation <operation-uuid>
/loreitems resume-operation <operation-uuid>
```

Pause acts on the parent operation. Already running Paper-thread work is allowed to finish its bounded attempt, but no new target claim should begin while the parent remains paused. Resume wakes naturally accessible work; it does not force-load chunks or offline inventories.

## Review-required targets

A target enters `REVIEW_REQUIRED` when the executor cannot prove that a mutation is safe or when an expired claim leaves uncertain side-effect evidence. Inspect the target before choosing a resolution.

```text
/loreitems resolve-removal <operation-uuid> <instance-uuid> requeue <evidence>
/loreitems resolve-removal <operation-uuid> <instance-uuid> removed <evidence>
/loreitems resolve-removal <operation-uuid> <instance-uuid> abort <evidence>
```

Use the actions narrowly:

- `requeue`: only when evidence shows that no physical side effect occurred and another safe attempt is required.
- `removed`: only when evidence proves that the intended physical copy was removed.
- `abort`: only when evidence shows that no side effect occurred and the target must not be retried.

The evidence text is mandatory and is committed to durable audit history. Do not use a review action to guess through a fingerprint mismatch, duplicate identity, malformed marker, or uncertain expired claim.

## Restart and reload recovery

- Reload clears unconfirmed previews but does not delete durable operations or targets.
- Shutdown stops new command output and worker activity without converting in-flight work into success.
- Startup recovery moves expired claims with uncertain side effects to `REVIEW_REQUIRED`.
- Naturally accessible inventories and entities are retried in bounded batches after resume or wakeup.
- Offline players and unloaded chunks are not force-loaded; their targets remain durable until natural access occurs.

After any crash or restart, inspect `destructive-metrics`, page active operations, and review every `REVIEW_REQUIRED` target before declaring the maintenance complete.
