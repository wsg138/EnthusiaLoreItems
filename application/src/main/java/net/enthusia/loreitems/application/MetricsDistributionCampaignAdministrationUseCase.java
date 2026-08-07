package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientState;
import net.enthusia.loreitems.domain.DistributionCampaign;

/** Adds operational campaign-control and review metrics without changing persistence semantics. */
public final class MetricsDistributionCampaignAdministrationUseCase
        implements DistributionCampaignAdministrationUseCase {
    private final DistributionCampaignAdministrationUseCase delegate;
    private final MetricsPort metrics;

    public MetricsDistributionCampaignAdministrationUseCase(
            DistributionCampaignAdministrationUseCase delegate, MetricsPort metrics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public CompletionStage<Page<DistributionCampaign>> listCampaigns(PageRequest request) {
        return delegate.listCampaigns(request);
    }

    @Override
    public CompletionStage<Optional<DistributionCampaignStatus>> status(UUID campaignId) {
        return delegate.status(campaignId);
    }

    @Override
    public CompletionStage<Page<CampaignRecipient>> listRecipients(
            UUID campaignId, CampaignRecipientState state, PageRequest request) {
        return delegate.listRecipients(campaignId, state, request);
    }

    @Override
    public CompletionStage<Page<CampaignRecipient>> listReviewRequired(PageRequest request) {
        return delegate.listReviewRequired(request).thenApply(page -> {
            metrics.setGauge("distribution.review.last_page_size", page.items().size());
            return page;
        });
    }

    @Override
    public CompletionStage<Boolean> pause(UUID campaignId, String actorType, String actorId) {
        return countTrue(
                delegate.pause(campaignId, actorType, actorId),
                "distribution.campaign.paused");
    }

    @Override
    public CompletionStage<Boolean> resume(UUID campaignId, String actorType, String actorId) {
        return countTrue(
                delegate.resume(campaignId, actorType, actorId),
                "distribution.campaign.resumed");
    }

    @Override
    public CompletionStage<CampaignCancellationResult> cancel(
            UUID campaignId, String actorType, String actorId) {
        return delegate.cancel(campaignId, actorType, actorId).thenApply(result -> {
            if (result.cancelled()) {
                metrics.increment("distribution.campaign.cancelled");
                metrics.setGauge(
                        "distribution.campaign.last_cancelled_recipients",
                        result.recipientsCancelled());
            }
            return result;
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
}
