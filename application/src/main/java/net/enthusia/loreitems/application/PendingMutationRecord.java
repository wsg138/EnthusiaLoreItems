package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.PendingMutationState;

public record PendingMutationRecord(
        UUID mutationId,
        String mutationType,
        LoreDefinitionId definitionId,
        LoreInstanceId instanceId,
        Integer desiredRevision,
        PendingMutationState state,
        String claimToken,
        Long claimExpiresAtEpochMillis,
        int attemptCount,
        Long nextAttemptAtEpochMillis,
        long createdAtEpochMillis,
        long updatedAtEpochMillis) {
    public static final int MAX_MUTATION_TYPE_LENGTH = 120;

    public PendingMutationRecord {
        Objects.requireNonNull(mutationId, "mutationId");
        Objects.requireNonNull(mutationType, "mutationType");
        Objects.requireNonNull(state, "state");
        mutationType = mutationType.strip();
        if (mutationType.isEmpty() || mutationType.length() > MAX_MUTATION_TYPE_LENGTH) {
            throw new IllegalArgumentException("Invalid mutation type");
        }
        if (desiredRevision != null && desiredRevision < 1) {
            throw new IllegalArgumentException("desiredRevision must be positive");
        }
        if ((claimToken == null) != (claimExpiresAtEpochMillis == null)) {
            throw new IllegalArgumentException("Claim token and expiry must be present together");
        }
        if (claimToken != null && claimToken.isBlank()) {
            throw new IllegalArgumentException("claimToken must not be blank");
        }
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount must not be negative");
        }
    }
}
