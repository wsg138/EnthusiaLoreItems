package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.application.DistributionCampaignStartRequest;
import net.enthusia.loreitems.domain.LoreDefinition;

public record DistributionCampaignPreview(
        GroupFileDefinition groupFile,
        LoreDefinition definition,
        DistributionCampaignStartRequest startRequest) {
    public DistributionCampaignPreview {
        Objects.requireNonNull(groupFile, "groupFile");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(startRequest, "startRequest");
        var campaign = startRequest.campaign();
        if (!campaign.sourceName().equals(groupFile.sourceName())
                || !campaign.sourceFingerprint().equals(groupFile.sourceFingerprint())
                || !campaign.displayName().equals(groupFile.displayName())) {
            throw new IllegalArgumentException("Preview campaign must match the immutable group snapshot");
        }
        if (!campaign.definitionId().equals(definition.id())
                || !campaign.definitionRevision().equals(definition.currentRevision())
                || !definition.active()) {
            throw new IllegalArgumentException("Preview campaign must pin the selected active definition");
        }
        if (startRequest.recipients().size() != groupFile.recipients().size()) {
            throw new IllegalArgumentException("Preview recipient count must match the group snapshot");
        }
    }

    public UUID campaignId() {
        return startRequest.campaign().campaignId();
    }
}
