package net.enthusia.loreitems.application;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.PendingMutationState;

public interface PendingMutationRepository {
    CompletionStage<Void> insert(PendingMutationRecord mutation);

    CompletionStage<Page<PendingMutationRecord>> claimPending(
            String claimToken, Instant now, Duration lease, int limit);

    CompletionStage<Boolean> transitionClaimed(
            UUID mutationId,
            PendingMutationState expected,
            PendingMutationState target,
            String claimToken,
            Instant now);

    CompletionStage<Integer> moveExpiredClaimsToReview(Instant now);

    CompletionStage<Page<PendingMutationRecord>> listNonTerminal(PageRequest request);
}
