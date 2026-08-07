package net.enthusia.loreitems.application;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientState;

public interface DistributionDeliveryExecutionUseCase {
    CompletionStage<Page<CampaignRecipient>> claimPending(int limit);

    CompletionStage<Optional<PreparedDistributionDelivery>> prepare(CampaignRecipient recipient);

    CompletionStage<Boolean> defer(
            CampaignRecipient recipient,
            CampaignRecipientState targetPendingState,
            Duration delay);

    CompletionStage<Boolean> defer(
            PreparedDistributionDelivery delivery,
            CampaignRecipientState targetPendingState,
            Duration delay);

    CompletionStage<Boolean> cancel(CampaignRecipient recipient);

    CompletionStage<Boolean> cancel(PreparedDistributionDelivery delivery);

    CompletionStage<Boolean> complete(
            PreparedDistributionDelivery delivery,
            int inventorySlot,
            String afterFingerprint);

    CompletionStage<Boolean> requireReview(CampaignRecipient recipient, String reason);

    CompletionStage<Boolean> requireReview(PreparedDistributionDelivery delivery, String reason);

    CompletionStage<Integer> wakePlayer(UUID playerId, int limit);

    CompletionStage<Integer> recoverExpiredClaims(int limit);
}
