package net.enthusia.loreitems.sqlite;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase.Observation;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase.PrepareResult;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase.PreparedRemoval;
import net.enthusia.loreitems.domain.DestructiveEffectState;

final class SQLiteDestructiveExecutionStore {
    private final SQLiteDestructiveClaimStore claims;
    private final SQLiteDestructiveCompletionStore completion;

    SQLiteDestructiveExecutionStore(SQLiteStorageRuntime storage) {
        Objects.requireNonNull(storage, "storage");
        this.claims = new SQLiteDestructiveClaimStore(storage);
        this.completion = new SQLiteDestructiveCompletionStore(storage);
    }

    CompletionStage<PrepareResult> prepareRemoval(
            Observation observation,
            String claimToken,
            Instant now,
            Duration lease) {
        return claims.prepareRemoval(observation, claimToken, now, lease);
    }

    CompletionStage<Boolean> releaseRemoval(
            PreparedRemoval removal,
            String reason,
            Instant now) {
        return completion.releaseRemoval(removal, reason, now);
    }

    CompletionStage<Boolean> completeRemoval(
            PreparedRemoval removal,
            String beforeFingerprint,
            Instant now) {
        return completion.completeRemoval(removal, beforeFingerprint, now);
    }

    CompletionStage<Boolean> requireRemovalReview(
            PreparedRemoval removal,
            DestructiveEffectState effectState,
            String beforeFingerprint,
            String afterFingerprint,
            String detail,
            Instant now) {
        return completion.requireRemovalReview(
                removal,
                effectState,
                beforeFingerprint,
                afterFingerprint,
                detail,
                now);
    }

    CompletionStage<Integer> moveExpiredClaimsToReview(Instant now, int limit) {
        return completion.moveExpiredClaimsToReview(now, limit);
    }
}