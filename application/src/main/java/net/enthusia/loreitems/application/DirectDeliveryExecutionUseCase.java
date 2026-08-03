package net.enthusia.loreitems.application;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface DirectDeliveryExecutionUseCase {
    CompletionStage<Page<PreparedDirectDelivery>> claimPending(int limit);

    CompletionStage<Boolean> defer(PreparedDirectDelivery delivery, Duration delay);

    CompletionStage<Boolean> complete(
            PreparedDirectDelivery delivery,
            int inventorySlot,
            String afterFingerprint);

    CompletionStage<Boolean> requireReview(
            PreparedDirectDelivery delivery,
            String reason);

    CompletionStage<Integer> wakePlayer(UUID playerId, int limit);

    CompletionStage<Integer> recoverExpiredClaims(int limit);
}
