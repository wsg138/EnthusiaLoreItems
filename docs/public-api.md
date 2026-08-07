# LoreItems public API V1

`net.enthusia.loreitems.api.v1.LoreItemsServiceV1` is the stable Bukkit service boundary for external plugins. V1 is intentionally small: consumers queue a durable lore-item delivery and receive the durable acceptance outcome asynchronously.

## Contract

```java
CompletionStage<LoreDeliveryResult> queueDelivery(
    String definitionKey,
    UUID playerId,
    String externalOperationId
);
```

`externalOperationId` is the caller-owned idempotency key. A caller must reuse the same key when replaying the same logical reward/claim after a timeout, plugin reload, server restart, or uncertain caller-side result. A replay must never be replaced with a newly generated key.

The returned stage represents durable request handling, not immediate inventory insertion. `ACCEPTED_QUEUED` means the intent is durable and physical delivery may happen later when the player is online and has inventory space. `ALREADY_ACCEPTED` is a successful idempotent replay of an already accepted operation. `UNKNOWN_DEFINITION`, `VALIDATION_FAILURE`, and `SERVICE_UNAVAILABLE` are non-acceptance outcomes and must not be treated as delivered rewards.

## Bukkit lookup

Consumers resolve the service from Bukkit's `ServicesManager` and must tolerate it being absent during LoreItems startup, reload replacement, shutdown, or degraded storage initialization. Do not dispatch LoreItems commands as an integration boundary.

```java
LoreItemsServiceV1 service = Bukkit.getServicesManager().load(LoreItemsServiceV1.class);
if (service == null) {
    // Preserve the caller's pending reward and retry later with the same externalOperationId.
    return;
}
service.queueDelivery(definitionKey, playerId, externalOperationId)
        .whenComplete((result, failure) -> {
            // Handle asynchronously; do not block the Paper main thread.
        });
```

## Compatibility policy

V1 package names, `API_VERSION`, method signature, result record components, and status names are pinned by compatibility tests. Existing V1 members are not removed or changed incompatibly. A future incompatible contract is published under a new versioned API package rather than mutating V1.

The API does not promise immediate delivery, command behavior, GUI behavior, repository internals, database schema access, or synchronous completion.
