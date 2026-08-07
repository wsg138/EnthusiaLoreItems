package net.enthusia.loreitems.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientState;
import net.enthusia.loreitems.domain.DistributionCampaign;
import net.enthusia.loreitems.domain.DistributionCampaignState;

public final class PersistingDistributionCampaignAdministrationUseCase
        implements DistributionCampaignAdministrationUseCase {
    private static final String AGGREGATE_TYPE = "DISTRIBUTION_CAMPAIGN";
    private static final String PAUSED_EVENT = "DISTRIBUTION_CAMPAIGN_PAUSED";
    private static final String RESUMED_EVENT = "DISTRIBUTION_CAMPAIGN_RESUMED";
    private static final String CANCELLED_EVENT = "DISTRIBUTION_CAMPAIGN_CANCELLED";

    private final DistributionCampaignRepository campaigns;
    private final DistributionRecipientRepository recipients;
    private final AuditRepository audit;
    private final Clock clock;

    public PersistingDistributionCampaignAdministrationUseCase(
            DistributionCampaignRepository campaigns,
            DistributionRecipientRepository recipients,
            AuditRepository audit,
            Clock clock) {
        this.campaigns = Objects.requireNonNull(campaigns, "campaigns");
        this.recipients = Objects.requireNonNull(recipients, "recipients");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<Page<DistributionCampaign>> listCampaigns(PageRequest request) {
        return campaigns.list(Objects.requireNonNull(request, "request"));
    }

    @Override
    public CompletionStage<Optional<DistributionCampaignStatus>> status(UUID campaignId) {
        Objects.requireNonNull(campaignId, "campaignId");
        return campaigns.findById(campaignId).thenCompose(campaign -> {
            if (campaign.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            return recipients.countByState(campaignId)
                    .thenApply(counts -> Optional.of(
                            new DistributionCampaignStatus(campaign.orElseThrow(), counts)));
        });
    }

    @Override
    public CompletionStage<Page<CampaignRecipient>> listRecipients(
            UUID campaignId,
            CampaignRecipientState state,
            PageRequest request) {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(request, "request");
        return state == null
                ? recipients.listByCampaign(campaignId, request)
                : recipients.listByCampaignAndState(campaignId, state, request);
    }

    @Override
    public CompletionStage<Boolean> pause(
            UUID campaignId,
            String actorType,
            String actorId) {
        return transition(
                campaignId,
                DistributionCampaignState.ACTIVE,
                DistributionCampaignState.PAUSED,
                PAUSED_EVENT,
                actorType,
                actorId);
    }

    @Override
    public CompletionStage<Boolean> resume(
            UUID campaignId,
            String actorType,
            String actorId) {
        return transition(
                campaignId,
                DistributionCampaignState.PAUSED,
                DistributionCampaignState.ACTIVE,
                RESUMED_EVENT,
                actorType,
                actorId);
    }

    @Override
    public CompletionStage<CampaignCancellationResult> cancel(
            UUID campaignId,
            String actorType,
            String actorId) {
        Objects.requireNonNull(campaignId, "campaignId");
        String normalizedActorType = requireActorType(actorType);
        String normalizedActorId = requireActorId(actorId);
        Instant now = clock.instant();
        return campaigns.findById(campaignId).thenCompose(existing -> {
            if (existing.isEmpty()
                    || existing.orElseThrow().state().terminal()) {
                return CompletableFuture.completedFuture(
                        new CampaignCancellationResult(false, 0));
            }
            DistributionCampaignState expected = existing.orElseThrow().state();
            return campaigns.cancel(campaignId, expected, now).thenCompose(result -> {
                if (!result.cancelled()) {
                    return CompletableFuture.completedFuture(result);
                }
                return appendAudit(
                                campaignId,
                                CANCELLED_EVENT,
                                normalizedActorType,
                                normalizedActorId,
                                "{\"recipientsCancelled\":"
                                        + result.recipientsCancelled() + "}",
                                now)
                        .thenApply(ignored -> result);
            });
        });
    }

    private CompletionStage<Boolean> transition(
            UUID campaignId,
            DistributionCampaignState expected,
            DistributionCampaignState target,
            String eventType,
            String actorType,
            String actorId) {
        Objects.requireNonNull(campaignId, "campaignId");
        String normalizedActorType = requireActorType(actorType);
        String normalizedActorId = requireActorId(actorId);
        Instant now = clock.instant();
        return campaigns.findById(campaignId).thenCompose(existing -> {
            if (existing.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            DistributionCampaignState current = existing.orElseThrow().state();
            if (current == target) {
                return CompletableFuture.completedFuture(true);
            }
            if (current != expected) {
                return CompletableFuture.completedFuture(false);
            }
            return campaigns.transitionState(campaignId, expected, target, now)
                    .thenCompose(changed -> {
                        if (!changed) {
                            return CompletableFuture.completedFuture(false);
                        }
                        return appendAudit(
                                        campaignId,
                                        eventType,
                                        normalizedActorType,
                                        normalizedActorId,
                                        "{\"from\":\"" + expected.name()
                                                + "\",\"to\":\"" + target.name() + "\"}",
                                        now)
                                .thenApply(ignored -> true);
                    });
        });
    }

    private CompletionStage<AuditEventRecord> appendAudit(
            UUID campaignId,
            String eventType,
            String actorType,
            String actorId,
            String detailJson,
            Instant now) {
        return audit.append(AuditEventRecord.pending(
                AGGREGATE_TYPE,
                campaignId.toString(),
                eventType,
                actorType,
                actorId,
                detailJson,
                now.toEpochMilli()));
    }

    private static String requireActorType(String actorType) {
        Objects.requireNonNull(actorType, "actorType");
        String normalized = actorType.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("actorType must not be blank");
        }
        return normalized;
    }

    private static String requireActorId(String actorId) {
        Objects.requireNonNull(actorId, "actorId");
        String normalized = actorId.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("actorId must not be blank");
        }
        return normalized;
    }
}
