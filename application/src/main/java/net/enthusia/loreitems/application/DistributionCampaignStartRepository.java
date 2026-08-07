package net.enthusia.loreitems.application;

import java.util.concurrent.CompletionStage;

public interface DistributionCampaignStartRepository {
    CompletionStage<DistributionCampaignStartResult> start(DistributionCampaignStartRequest request);
}
