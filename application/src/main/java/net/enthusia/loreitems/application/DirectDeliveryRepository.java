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

    CompletionStage<Boolean> transitionClaimed(
            UUID deliveryId,
            DirectDeliveryState expected,
            DirectDeliveryState target,
            String claimToken,
            Instant now);

    CompletionStage<Integer> moveExpiredClaimsToReview(Instant now, int limit);

    CompletionStage<Page<DirectDeliveryRecord>> listNonTerminal(PageRequest request);
}
