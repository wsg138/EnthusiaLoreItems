package net.enthusia.loreitems.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientState;

/** Adds bounded operational counters around campaign delivery outcomes. */
public final class MetricsDistributionDeliveryExecutionUseCase
        implements DistributionDeliveryExecutionUseCase {
    private final DistributionDeliveryExecutionUseCase delegate;
    private final MetricsPort metrics;

    public MetricsDistributionDeliveryExecutionUseCase(
            DistributionDeliveryExecutionUseCase delegate, MetricsPort metrics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public CompletionStage<Page<CampaignRecipient>> claimPending(int limit) {
        return delegate.claimPending(limit).thenApply(page -> {
            metrics.increment("distribution.delivery.claim_batch");
            metrics.setGauge("distribution.delivery.last_claimed", page.items().size());
            return page;
        });
    }

    @Override
    public CompletionStage<Optional<PreparedDistributionDelivery>> prepare(
            CampaignRecipient recipient) {
        return delegate.prepare(recipient).thenApply(prepared -> {
            if (prepared.isPresent()) {
                metrics.increment("distribution.delivery.prepared");
            }
            return prepared;
        });
    }

    @Override
    public CompletionStage<Boolean> defer(
            CampaignRecipient recipient,
            CampaignRecipientState targetPendingState,
            Duration delay) {
        return countTrue(
                delegate.defer(recipient, targetPendingState, delay),
                deferMetric(targetPendingState));
    }

    @Override
    public CompletionStage<Boolean> defer(
            PreparedDistributionDelivery delivery,
            CampaignRecipientState targetPendingState,
            Duration delay) {
        return countTrue(
                delegate.defer(delivery, targetPendingState, delay),
                deferMetric(targetPendingState));
    }

    @Override
    public CompletionStage<Boolean> cancel(CampaignRecipient recipient) {
        return countTrue(delegate.cancel(recipient), "distribution.delivery.cancelled");
    }

    @Override
    public CompletionStage<Boolean> cancel(PreparedDistributionDelivery delivery) {
        return countTrue(delegate.cancel(delivery), "distribution.delivery.cancelled");
    }

    @Override
    public CompletionStage<Boolean> complete(
            PreparedDistributionDelivery delivery,
            int inventorySlot,
            String afterFingerprint) {
        return countTrue(
                delegate.complete(delivery, inventorySlot, afterFingerprint),
                "distribution.delivery.delivered");
    }

    @Override
    public CompletionStage<Boolean> requireReview(
            CampaignRecipient recipient, String reason) {
        return countTrue(
                delegate.requireReview(recipient, reason),
                "distribution.delivery.review_required");
    }

    @Override
    public CompletionStage<Boolean> requireReview(
            PreparedDistributionDelivery delivery, String reason) {
        return countTrue(
                delegate.requireReview(delivery, reason),
                "distribution.delivery.review_required");
    }

    @Override
    public CompletionStage<Integer> wakePlayer(UUID playerId, int limit) {
        return delegate.wakePlayer(playerId, limit).thenApply(woken -> {
            metrics.setGauge("distribution.delivery.last_woken", woken);
            return woken;
        });
    }

    @Override
    public CompletionStage<Integer> recoverExpiredClaims(int limit) {
        return delegate.recoverExpiredClaims(limit).thenApply(recovered -> {
            if (recovered > 0) {
                metrics.increment("distribution.delivery.expired_claim_recovery");
            }
            metrics.setGauge("distribution.delivery.last_recovered", recovered);
            return recovered;
        });
    }

    private CompletionStage<Boolean> countTrue(CompletionStage<Boolean> stage, String metric) {
        return stage.thenApply(changed -> {
            if (Boolean.TRUE.equals(changed)) {
                metrics.increment(metric);
            }
            return changed;
        });
    }

    private static String deferMetric(CampaignRecipientState target) {
        return switch (Objects.requireNonNull(target, "target")) {
            case QUEUED_OFFLINE -> "distribution.delivery.deferred_offline";
            case QUEUED_INVENTORY_FULL -> "distribution.delivery.deferred_inventory_full";
            default -> "distribution.delivery.deferred_other";
        };
    }
}
