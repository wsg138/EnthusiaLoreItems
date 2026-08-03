package net.enthusia.loreitems.application;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public interface HeldItemAdoptionStore {
    CompletionStage<Optional<PreparedHeldItemAdoption>> prepare(
            HeldItemAdoptionPreparation preparation);

    CompletionStage<Boolean> complete(
            PreparedHeldItemAdoption adoption,
            String afterFingerprint,
            Instant completedAt);

    CompletionStage<Boolean> requireReview(
            PreparedHeldItemAdoption adoption,
            String reason,
            Instant reviewedAt);
}
