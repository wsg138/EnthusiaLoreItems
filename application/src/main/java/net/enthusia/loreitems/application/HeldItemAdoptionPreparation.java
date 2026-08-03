package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.UUID;

public record HeldItemAdoptionPreparation(
        PrepareHeldItemAdoptionRequest request,
        UUID mutationId,
        UUID instanceId,
        UUID claimToken,
        long preparedAtEpochMillis,
        long claimExpiresAtEpochMillis) {
    private static final long EARLIEST_EPOCH_MILLIS = 0L;

    public HeldItemAdoptionPreparation {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(mutationId, "mutationId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(claimToken, "claimToken");
        if (preparedAtEpochMillis < EARLIEST_EPOCH_MILLIS) {
            throw new IllegalArgumentException("preparedAtEpochMillis must not be negative");
        }
        if (claimExpiresAtEpochMillis <= preparedAtEpochMillis) {
            throw new IllegalArgumentException("Adoption claim must expire after preparation");
        }
    }
}
