package net.enthusia.loreitems.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientKey;
import net.enthusia.loreitems.domain.CampaignRecipientState;
import net.enthusia.loreitems.domain.LoreInstanceId;

public interface DistributionRecipientRepository {
    int MAX_INSERT_BATCH = PageRequest.MAX_LIMIT;

    CompletionStage<Void> insertBatch(UUID campaignId, List<CampaignRecipient> recipients);

    CompletionStage<Optional<CampaignRecipient>> find(
            UUID campaignId, CampaignRecipientKey recipientKey);

    CompletionStage<Page<CampaignRecipient>> listByCampaign(
            UUID campaignId, PageRequest request);

    CompletionStage<Page<CampaignRecipient>> listByCampaignAndState(
            UUID campaignId, CampaignRecipientState state, PageRequest request);

    CompletionStage<Page<CampaignRecipient>> listUnresolvedByKey(
            CampaignRecipientKey recipientKey, PageRequest request);

    CompletionStage<CampaignRecipientCounts> countByState(UUID campaignId);

    CompletionStage<Boolean> bindUnresolvedName(
            UUID campaignId,
            CampaignRecipientKey recipientKey,
            UUID playerId,
            Instant now);

    CompletionStage<Page<CampaignRecipient>> claimPending(
            UUID campaignId,
            String claimToken,
            Instant now,
            Duration lease,
            int limit);

    CompletionStage<Boolean> releaseClaim(
            UUID campaignId,
            CampaignRecipientKey recipientKey,
            CampaignRecipientState targetPendingState,
            String claimToken,
            Instant now,
            Instant nextAttemptAt);

    CompletionStage<Boolean> completeClaim(
            UUID campaignId,
            CampaignRecipientKey recipientKey,
            String claimToken,
            LoreInstanceId instanceId,
            Instant deliveredAt);

    CompletionStage<Boolean> moveClaimToReview(
            UUID campaignId,
            CampaignRecipientKey recipientKey,
            String claimToken,
            LoreInstanceId instanceId,
            Instant now);

    CompletionStage<Integer> moveExpiredClaimsToReview(Instant now, int limit);
}
