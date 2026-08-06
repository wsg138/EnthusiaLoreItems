package net.enthusia.loreitems.application;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.DestructiveEffectState;

public final class PersistingDestructiveRemovalExecutionUseCase
        implements DestructiveRemovalExecutionUseCase {
    private final DestructiveOperationStore store;
    private final Clock clock;
    private final Duration claimLease;

    public PersistingDestructiveRemovalExecutionUseCase(
            DestructiveOperationStore store,
            Clock clock,
            Duration claimLease) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.claimLease = Objects.requireNonNull(claimLease, "claimLease");
        if (claimLease.isZero() || claimLease.isNegative()) {
            throw new IllegalArgumentException("claimLease must be positive");
        }
    }

    @Override
    public CompletionStage<PrepareResult> prepare(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        return store.prepareRemoval(
                observation,
                UUID.randomUUID().toString(),
                clock.instant(),
                claimLease);
    }

    @Override
    public CompletionStage<Boolean> release(PreparedRemoval removal, String reason) {
        return store.releaseRemoval(
                Objects.requireNonNull(removal, "removal"),
                requireDetail(reason, "reason"),
                clock.instant());
    }

    @Override
    public CompletionStage<Boolean> complete(
            PreparedRemoval removal,
            String beforeFingerprint) {
        return store.completeRemoval(
                Objects.requireNonNull(removal, "removal"),
                requireDetail(beforeFingerprint, "beforeFingerprint"),
                clock.instant());
    }

    @Override
    public CompletionStage<Boolean> requireReview(
            PreparedRemoval removal,
            DestructiveEffectState effectState,
            String beforeFingerprint,
            String afterFingerprint,
            String detail) {
        return store.requireRemovalReview(
                Objects.requireNonNull(removal, "removal"),
                persistedEffectState(effectState),
                normalizeNullable(beforeFingerprint),
                normalizeNullable(afterFingerprint),
                requireDetail(detail, "detail"),
                clock.instant());
    }

    private static DestructiveEffectState persistedEffectState(
            DestructiveEffectState effectState) {
        DestructiveEffectState observed = Objects.requireNonNull(effectState, "effectState");
        return observed == DestructiveEffectState.UNKNOWN
                ? DestructiveEffectState.AMBIGUOUS
                : observed;
    }

    private static String requireDetail(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }
}
