package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.UUID;

public record DistributionCampaignStartResult(Status status, UUID campaignId) {
    public DistributionCampaignStartResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(campaignId, "campaignId");
    }

    public enum Status {
        STARTED,
        SOURCE_ALREADY_USED
    }
}
