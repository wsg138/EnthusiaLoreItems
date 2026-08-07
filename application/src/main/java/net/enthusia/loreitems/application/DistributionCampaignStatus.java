package net.enthusia.loreitems.application;

import java.util.Objects;
import net.enthusia.loreitems.domain.DistributionCampaign;

public record DistributionCampaignStatus(
        DistributionCampaign campaign,
        CampaignRecipientCounts recipientCounts) {
    public DistributionCampaignStatus {
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(recipientCounts, "recipientCounts");
    }
}
