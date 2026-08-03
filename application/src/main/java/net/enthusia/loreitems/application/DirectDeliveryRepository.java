package net.enthusia.loreitems.application;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.DirectDeliveryState;

public interface DirectDeliveryRepository {
    CompletionStage<ExternalDeliveryAcceptance> acceptExternal(
            ExternalDeliveryCommand command, Instant now);

    CompletionStage<Page<DirectDeliveryRecord>> claimPending(
            String claimToken, Instant now, Duration lease, int limit);

    CompletionStage<Page<PreparedDirectDelivery>> claimPreparedPending(
            String claimToken, Instant now, Duration lease, int limit);

    CompletionStage<Boolean> deferClaimed(
            UUID deliveryId,
            String claimToken,
            Instant now,
            Instant nextAttemptAt);

    CompletionStage<Boolean> completeClaimed(
            PreparedDirectDelivery delivery,
            int inventorySlot,
            String afterFingerprint,
            Instant completedAt);

    CompletionStage<Boolean> moveClaimedToReview(
            PreparedDirectDelivery delivery,
            String reason,
            Instant reviewedAt);

    CompletionStage<Integer> wakePendingForPlayer(
            UUID playerId,
            Instant now,
            int limit);

    CompletionStage<Boolean> transitionClaimed(
            UUID deliveryId,
            DirectDeliveryState expected,
            DirectDeliveryState target,
            String claimToken,
            Instant now);

    CompletionStage<Integer> moveExpiredClaimsToReview(Instant now, int limit);

    CompletionStage<Page<DirectDeliveryRecord>> listNonTerminal(PageRequest request);
}
