package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.domain.DirectDeliveryState;
import net.enthusia.loreitems.domain.LoreInstanceId;

public record DirectDeliveryRecord(
        UUID deliveryId,
        LoreInstanceId instanceId,
        UUID playerId,
        DirectDeliveryState state,
        String idempotencyKey,
        String claimToken,
        Long claimExpiresAtEpochMillis,
        int attemptCount,
        long createdAtEpochMillis,
        long updatedAtEpochMillis) {
    public DirectDeliveryRecord {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount must not be negative");
        }
    }
}
