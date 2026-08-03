package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.UUID;

public record PreparedVoidLoss(
        UUID mutationId,
        LoreItemIdentity identity,
        UUID entityId,
        String locationKey,
        UUID claimToken,
        long preparedAtEpochMillis,
        long claimExpiresAtEpochMillis) {
    private static final long MIN_TIMESTAMP = 0L;

    public PreparedVoidLoss {
        Objects.requireNonNull(mutationId, "mutationId");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(locationKey, "locationKey");
        Objects.requireNonNull(claimToken, "claimToken");
        locationKey = locationKey.strip();
        if (locationKey.isEmpty() || locationKey.length() > 512) {
            throw new IllegalArgumentException("Invalid void location key");
        }
        if (preparedAtEpochMillis < MIN_TIMESTAMP) {
            throw new IllegalArgumentException("preparedAtEpochMillis must not be negative");
        }
        if (claimExpiresAtEpochMillis <= preparedAtEpochMillis) {
            throw new IllegalArgumentException("claim expiry must follow preparation time");
        }
    }
}
