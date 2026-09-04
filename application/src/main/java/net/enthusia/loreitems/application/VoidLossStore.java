package net.enthusia.loreitems.application;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface VoidLossStore {
    String MUTATION_TYPE = "VOID_TERMINAL_LOSS";

    CompletionStage<VoidLossUseCase.PrepareResult> prepare(
            VoidLossUseCase.Request request,
            UUID mutationId,
            UUID claimToken,
            Instant preparedAt,
            Instant claimExpiresAt);

    CompletionStage<Boolean> complete(PreparedVoidLoss loss, Instant completedAt);

    CompletionStage<Boolean> abort(
            PreparedVoidLoss loss,
            String reason,
            Instant abortedAt);

    CompletionStage<Boolean> requireReview(
            PreparedVoidLoss loss,
            String reason,
            Instant reviewedAt);
}
