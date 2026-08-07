package net.enthusia.loreitems.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientState;

public final class PersistingDistributionDeliveryExecutionUseCase
        implements DistributionDeliveryExecutionUseCase {
    private static final String RECIPIENT_ARGUMENT = "recipient";
    private static final String DELIVERY_ARGUMENT = "delivery";

    private final DistributionDeliveryRepository repository;
    private final Clock clock;
    private final Duration claimLease;
    private final Supplier<UUID> claimIds;

    public PersistingDistributionDeliveryExecutionUseCase(
            DistributionDeliveryRepository repository,
            Clock clock,
            Duration claimLease) {
        this(repository, clock, claimLease, UUID::randomUUID);
    }

    PersistingDistributionDeliveryExecutionUseCase(
            DistributionDeliveryRepository repository,
            Clock clock,
            Duration claimLease,
            Supplier<UUID> claimIds) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.claimLease = Objects.requireNonNull(claimLease, "claimLease");
        this.claimIds = Objects.requireNonNull(claimIds, "claimIds");
        if (claimLease.isZero() || claimLease.isNegative()) {
            throw new IllegalArgumentException("claimLease must be positive");
        }
    }

    @Override
    public CompletionStage<Page<CampaignRecipient>> claimPending(int limit) {
        return repository.claimPending(claimToken(), clock.instant(), claimLease, limit);
    }

    @Override
    public CompletionStage<Optional<PreparedDistributionDelivery>> prepare(
            CampaignRecipient recipient) {
        Objects.requireNonNull(recipient, RECIPIENT_ARGUMENT);
        return repository.prepareClaimed(recipient, clock.instant());
    }

    @Override
    public CompletionStage<Boolean> defer(
            CampaignRecipient recipient,
            CampaignRecipientState targetPendingState,
            Duration delay) {
        Objects.requireNonNull(recipient, RECIPIENT_ARGUMENT);
        Instant now = clock.instant();
        return repository.deferClaimed(
                recipient,
                targetPendingState,
                now,
                delayed(now, delay));
    }

    @Override
    public CompletionStage<Boolean> defer(
            PreparedDistributionDelivery delivery,
            CampaignRecipientState targetPendingState,
            Duration delay) {
        Objects.requireNonNull(delivery, DELIVERY_ARGUMENT);
        Instant now = clock.instant();
        return repository.deferPrepared(
                delivery,
                targetPendingState,
                now,
                delayed(now, delay));
    }

    @Override
    public CompletionStage<Boolean> cancel(CampaignRecipient recipient) {
        return repository.cancelClaimed(
                Objects.requireNonNull(recipient, RECIPIENT_ARGUMENT),
                clock.instant());
    }

    @Override
    public CompletionStage<Boolean> cancel(PreparedDistributionDelivery delivery) {
        return repository.cancelPrepared(
                Objects.requireNonNull(delivery, DELIVERY_ARGUMENT),
                clock.instant());
    }

    @Override
    public CompletionStage<Boolean> complete(
            PreparedDistributionDelivery delivery,
            int inventorySlot,
            String afterFingerprint) {
        return repository.completePrepared(
                Objects.requireNonNull(delivery, DELIVERY_ARGUMENT),
                inventorySlot,
                afterFingerprint,
                clock.instant());
    }

    @Override
    public CompletionStage<Boolean> requireReview(
            CampaignRecipient recipient,
            String reason) {
        return repository.moveClaimedToReview(
                Objects.requireNonNull(recipient, RECIPIENT_ARGUMENT),
                reason,
                clock.instant());
    }

    @Override
    public CompletionStage<Boolean> requireReview(
            PreparedDistributionDelivery delivery,
            String reason) {
        return repository.movePreparedToReview(
                Objects.requireNonNull(delivery, DELIVERY_ARGUMENT),
                reason,
                clock.instant());
    }

    @Override
    public CompletionStage<Integer> wakePlayer(UUID playerId, int limit) {
        return repository.wakePlayer(
                Objects.requireNonNull(playerId, "playerId"),
                clock.instant(),
                limit);
    }

    @Override
    public CompletionStage<Integer> recoverExpiredClaims(int limit) {
        return repository.recoverExpiredClaims(clock.instant(), limit);
    }

    private String claimToken() {
        return Objects.requireNonNull(claimIds.get(), "claimId").toString();
    }

    private static Instant delayed(Instant now, Duration delay) {
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        return now.plus(delay);
    }
}
