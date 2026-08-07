package net.enthusia.loreitems.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientState;
import net.enthusia.loreitems.domain.DistributionCampaign;

public interface DistributionCampaignAdministrationUseCase {
    CompletionStage<Page<DistributionCampaign>> listCampaigns(PageRequest request);

    CompletionStage<Optional<DistributionCampaignStatus>> status(UUID campaignId);

    CompletionStage<Page<CampaignRecipient>> listRecipients(
            UUID campaignId,
            CampaignRecipientState state,
            PageRequest request);

    default CompletionStage<Page<CampaignRecipient>> listReviewRequired(PageRequest request) {
        return CompletableFuture.completedFuture(
                new Page<>(List.of(), request.offset(), request.limit(), false));
    }

    CompletionStage<Boolean> pause(UUID campaignId, String actorType, String actorId);

    CompletionStage<Boolean> resume(UUID campaignId, String actorType, String actorId);

    CompletionStage<CampaignCancellationResult> cancel(
            UUID campaignId,
            String actorType,
            String actorId);
}
