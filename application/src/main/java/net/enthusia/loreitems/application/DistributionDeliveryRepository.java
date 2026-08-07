package net.enthusia.loreitems.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientState;

public interface DistributionDeliveryRepository {
    CompletionStage<Page<CampaignRecipient>> claimPending(
            String claimToken,
            Instant now,
            Duration lease,
            int limit);

    CompletionStage<Optional<PreparedDistributionDelivery>> prepareClaimed(
            CampaignRecipient recipient,
            Instant now);

    CompletionStage<Boolean> deferClaimed(
            CampaignRecipient recipient,
            CampaignRecipientState targetPendingState,
            Instant now,
            Instant nextAttemptAt);

    CompletionStage<Boolean> deferPrepared(
            PreparedDistributionDelivery delivery,
            CampaignRecipientState targetPendingState,
            Instant now,
            Instant nextAttemptAt);

    CompletionStage<Boolean> completePrepared(
            PreparedDistributionDelivery delivery,
            int inventorySlot,
            String afterFingerprint,
            Instant completedAt);

    CompletionStage<Boolean> moveClaimedToReview(
            CampaignRecipient recipient,
            String reason,
            Instant reviewedAt);

    CompletionStage<Boolean> movePreparedToReview(
            PreparedDistributionDelivery delivery,
            String reason,
            Instant reviewedAt);

    CompletionStage<Integer> wakePlayer(UUID playerId, Instant now, int limit);

    CompletionStage<Integer> recoverExpiredClaims(Instant now, int limit);
}
