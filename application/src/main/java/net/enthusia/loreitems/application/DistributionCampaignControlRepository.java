package net.enthusia.loreitems.application;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.DistributionCampaignState;

/** Atomic persistence boundary for campaign control state and its required audit evidence. */
public interface DistributionCampaignControlRepository {
    CompletionStage<Boolean> transitionWithAudit(
            UUID campaignId,
            DistributionCampaignState expected,
            DistributionCampaignState target,
            Instant now,
            AuditEventRecord auditEvent);

    CompletionStage<CampaignCancellationResult> cancelWithAudit(
            UUID campaignId,
            DistributionCampaignState expected,
            Instant now,
            String eventType,
            String actorType,
            String actorId);
}
