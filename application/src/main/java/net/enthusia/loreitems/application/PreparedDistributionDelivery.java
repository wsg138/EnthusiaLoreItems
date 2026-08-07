package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.domain.CampaignRecipientKey;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;

public record PreparedDistributionDelivery(
        UUID campaignId,
        CampaignRecipientKey recipientKey,
        LoreInstanceId instanceId,
        LoreDefinitionId definitionId,
        UUID playerId,
        TemplateRevision appliedRevision,
        EncodedItemTemplate template,
        String claimToken,
        long claimExpiresAtEpochMillis,
        int attemptCount,
        long updatedAtEpochMillis) {
    public PreparedDistributionDelivery {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(recipientKey, "recipientKey");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(appliedRevision, "appliedRevision");
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(claimToken, "claimToken");
        claimToken = claimToken.strip();
        if (claimToken.isEmpty()) {
            throw new IllegalArgumentException("claimToken must not be blank");
        }
        if (claimExpiresAtEpochMillis <= updatedAtEpochMillis) {
            throw new IllegalArgumentException("Prepared campaign claim must remain live");
        }
        if (attemptCount < 1 || updatedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("Invalid campaign delivery counters or timestamps");
        }
    }

    public LoreItemIdentity identity() {
        return new LoreItemIdentity(definitionId, instanceId, appliedRevision);
    }
}
