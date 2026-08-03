package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;

public record PreparedHeldItemAdoption(
        UUID mutationId,
        DefinitionKey definitionKey,
        LoreDefinitionId definitionId,
        LoreInstanceId instanceId,
        TemplateRevision appliedRevision,
        UUID playerId,
        int selectedSlot,
        String beforeFingerprint,
        UUID claimToken,
        long preparedAtEpochMillis,
        long claimExpiresAtEpochMillis) {
    public PreparedHeldItemAdoption {
        Objects.requireNonNull(mutationId, "mutationId");
        Objects.requireNonNull(definitionKey, "definitionKey");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(appliedRevision, "appliedRevision");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(beforeFingerprint, "beforeFingerprint");
        Objects.requireNonNull(claimToken, "claimToken");
        PrepareHeldItemAdoptionRequest normalized = new PrepareHeldItemAdoptionRequest(
                definitionKey, playerId, selectedSlot, beforeFingerprint);
        beforeFingerprint = normalized.beforeFingerprint();
        if (preparedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("preparedAtEpochMillis must not be negative");
        }
        if (claimExpiresAtEpochMillis <= preparedAtEpochMillis) {
            throw new IllegalArgumentException("Adoption claim must expire after preparation");
        }
    }

    public LoreItemIdentity identity() {
        return new LoreItemIdentity(definitionId, instanceId, appliedRevision);
    }
}
