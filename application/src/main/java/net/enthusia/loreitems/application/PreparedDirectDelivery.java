package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.domain.DirectDeliveryState;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;

public record PreparedDirectDelivery(
        UUID deliveryId,
        LoreInstanceId instanceId,
        LoreDefinitionId definitionId,
        UUID playerId,
        TemplateRevision appliedRevision,
        EncodedItemTemplate template,
        String idempotencyKey,
        String claimToken,
        long claimExpiresAtEpochMillis,
        int attemptCount,
        long createdAtEpochMillis,
        long updatedAtEpochMillis) {
    private static final long MIN_TIMESTAMP = 0L;
    private static final int MIN_ATTEMPT_COUNT = 1;

    public PreparedDirectDelivery {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(appliedRevision, "appliedRevision");
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(claimToken, "claimToken");
        idempotencyKey = idempotencyKey.strip();
        claimToken = claimToken.strip();
        if (idempotencyKey.isEmpty() || claimToken.isEmpty()) {
            throw new IllegalArgumentException("Delivery identifiers must not be blank");
        }
        if (claimExpiresAtEpochMillis < MIN_TIMESTAMP
                || createdAtEpochMillis < MIN_TIMESTAMP
                || updatedAtEpochMillis < MIN_TIMESTAMP) {
            throw new IllegalArgumentException("Delivery timestamps must not be negative");
        }
        if (claimExpiresAtEpochMillis <= updatedAtEpochMillis) {
            throw new IllegalArgumentException("Delivery claim must expire after its update time");
        }
        if (attemptCount < MIN_ATTEMPT_COUNT) {
            throw new IllegalArgumentException("Prepared delivery attemptCount must be positive");
        }
    }

    public LoreItemIdentity identity() {
        return new LoreItemIdentity(definitionId, instanceId, appliedRevision);
    }

    public DirectDeliveryRecord record() {
        return new DirectDeliveryRecord(
                deliveryId,
                instanceId,
                playerId,
                DirectDeliveryState.RESERVED,
                idempotencyKey,
                claimToken,
                claimExpiresAtEpochMillis,
                attemptCount,
                createdAtEpochMillis,
                updatedAtEpochMillis);
    }
}
