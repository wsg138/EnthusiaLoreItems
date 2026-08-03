package net.enthusia.loreitems.application;

import java.util.concurrent.CompletionStage;

public interface AdoptHeldItemUseCase {
    CompletionStage<PrepareHeldItemAdoptionResult> prepare(
            PrepareHeldItemAdoptionRequest request);

    CompletionStage<Boolean> complete(
            PreparedHeldItemAdoption adoption,
            String afterFingerprint);

    CompletionStage<Boolean> requireReview(
            PreparedHeldItemAdoption adoption,
            String reason);
}
