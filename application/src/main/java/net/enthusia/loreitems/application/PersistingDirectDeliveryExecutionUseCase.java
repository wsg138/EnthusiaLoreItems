package net.enthusia.loreitems.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class PersistingDirectDeliveryExecutionUseCase
        implements DirectDeliveryExecutionUseCase {
    private static final int MIN_INVENTORY_SLOT = 0;
    private static final int MAX_PLAYER_INVENTORY_SLOT = 35;

    private final DirectDeliveryRepository repository;
    private final Clock clock;
    private final Duration claimLease;

    public PersistingDirectDeliveryExecutionUseCase(
            DirectDeliveryRepository repository,
            Clock clock,
            Duration claimLease) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.claimLease = Objects.requireNonNull(claimLease, "claimLease");
        if (claimLease.isZero() || claimLease.isNegative()) {
            throw new IllegalArgumentException("claimLease must be positive");
        }
    }

    @Override
    public CompletionStage<Page<PreparedDirectDelivery>> claimPending(int limit) {
        Instant now = clock.instant();
        return repository.claimPreparedPending(
                UUID.randomUUID().toString(), now, claimLease, limit);
    }

    @Override
    public CompletionStage<Boolean> defer(
            PreparedDirectDelivery delivery,
            Duration delay) {
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        Instant now = clock.instant();
        return repository.deferClaimed(
                delivery.deliveryId(),
                delivery.claimToken(),
                now,
                now.plus(delay));
    }

    @Override
    public CompletionStage<Boolean> complete(
            PreparedDirectDelivery delivery,
            int inventorySlot,
            String afterFingerprint) {
        Objects.requireNonNull(delivery, "delivery");
        if (inventorySlot < MIN_INVENTORY_SLOT || inventorySlot > MAX_PLAYER_INVENTORY_SLOT) {
            throw new IllegalArgumentException("inventorySlot must identify player storage");
        }
        return repository.completeClaimed(
                delivery,
                inventorySlot,
                afterFingerprint,
                clock.instant());
    }

    @Override
    public CompletionStage<Boolean> requireReview(
            PreparedDirectDelivery delivery,
            String reason) {
        Objects.requireNonNull(delivery, "delivery");
        return repository.moveClaimedToReview(delivery, reason, clock.instant());
    }

    @Override
    public CompletionStage<Integer> wakePlayer(UUID playerId, int limit) {
        return repository.wakePendingForPlayer(
                Objects.requireNonNull(playerId, "playerId"),
                clock.instant(),
                limit);
    }

    @Override
    public CompletionStage<Integer> recoverExpiredClaims(int limit) {
        return repository.moveExpiredClaimsToReview(clock.instant(), limit);
    }
}
