package net.enthusia.loreitems.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientKey;

public final class BindDistributionRecipientsUseCase {
    private final DistributionRecipientRepository recipients;

    public BindDistributionRecipientsUseCase(DistributionRecipientRepository recipients) {
        this.recipients = Objects.requireNonNull(recipients, "recipients");
    }

    public CompletionStage<DistributionRecipientBindingBatch> bindCurrentName(
            UUID playerId,
            String currentName,
            Instant now,
            int limit) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(currentName, "currentName");
        Objects.requireNonNull(now, "now");
        PageRequest request = PageRequest.first(limit);
        CampaignRecipientKey key = CampaignRecipientKey.forUnresolvedName(currentName);
        return recipients.listUnresolvedByKey(key, request).thenCompose(page ->
                bindPage(page, key, playerId, now));
    }

    private CompletionStage<DistributionRecipientBindingBatch> bindPage(
            Page<CampaignRecipient> page,
            CampaignRecipientKey key,
            UUID playerId,
            Instant now) {
        CompletionStage<MutableCounts> stage = CompletableFuture.completedFuture(new MutableCounts());
        for (CampaignRecipient recipient : page.items()) {
            stage = stage.thenCompose(counts -> recipients.bindUnresolvedName(
                            recipient.campaignId(), key, playerId, now)
                    .thenApply(bound -> counts.record(bound)));
        }
        return stage.thenApply(counts -> new DistributionRecipientBindingBatch(
                page.items().size(),
                counts.bound,
                counts.notBound,
                page.hasMore()));
    }

    private static final class MutableCounts {
        private int bound;
        private int notBound;

        private MutableCounts record(boolean wasBound) {
            if (wasBound) {
                bound++;
            } else {
                notBound++;
            }
            return this;
        }
    }
}
