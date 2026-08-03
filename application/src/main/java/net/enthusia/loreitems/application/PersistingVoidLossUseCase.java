package net.enthusia.loreitems.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class PersistingVoidLossUseCase implements VoidLossUseCase {
    private static final int MAX_REASON_LENGTH = 4_096;

    private final VoidLossStore store;
    private final Clock clock;
    private final Duration claimLease;

    public PersistingVoidLossUseCase(
            VoidLossStore store,
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
    public CompletionStage<PrepareResult> prepare(Request request) {
        Objects.requireNonNull(request, "request");
        Instant preparedAt = clock.instant();
        return store.prepare(
                request,
                UUID.randomUUID(),
                UUID.randomUUID(),
                preparedAt,
                preparedAt.plus(claimLease));
    }

    @Override
    public CompletionStage<Boolean> complete(PreparedVoidLoss loss) {
        return store.complete(Objects.requireNonNull(loss, "loss"), clock.instant());
    }

    @Override
    public CompletionStage<Boolean> abort(PreparedVoidLoss loss, String reason) {
        return store.abort(
                Objects.requireNonNull(loss, "loss"),
                requireReason(reason),
                clock.instant());
    }

    @Override
    public CompletionStage<Boolean> requireReview(PreparedVoidLoss loss, String reason) {
        return store.requireReview(
                Objects.requireNonNull(loss, "loss"),
                requireReason(reason),
                clock.instant());
    }

    private static String requireReason(String reason) {
        Objects.requireNonNull(reason, "reason");
        String normalized = reason.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("Invalid void-loss reason");
        }
        return normalized;
    }
}
