package net.enthusia.loreitems.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.DistributionCampaign;
import net.enthusia.loreitems.domain.DistributionCampaignState;

public interface DistributionCampaignRepository {
    CompletionStage<Void> create(DistributionCampaign campaign);

    CompletionStage<Optional<DistributionCampaign>> findById(UUID campaignId);

    CompletionStage<Optional<DistributionCampaign>> findBySourceFingerprint(
            String sourceFingerprint);

    CompletionStage<Page<DistributionCampaign>> list(PageRequest request);

    CompletionStage<Boolean> transitionState(
            UUID campaignId,
            DistributionCampaignState expected,
            DistributionCampaignState target,
            Instant now);

    CompletionStage<CampaignCancellationResult> cancel(
            UUID campaignId,
            DistributionCampaignState expected,
            Instant now);
}
