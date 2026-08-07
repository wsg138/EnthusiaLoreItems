package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.enthusia.loreitems.application.PreparedDistributionDelivery;
import net.enthusia.loreitems.domain.CampaignRecipient;

final class PaperDistributionCancellationGate implements AutoCloseable {
    private final int perCampaignHoldLimit;
    private final Set<UUID> fences = new HashSet<>();
    private final Set<UUID> committed = new HashSet<>();
    private final Map<UUID, List<CampaignRecipient>> heldClaims = new HashMap<>();
    private final Map<UUID, List<PreparedDistributionDelivery>> heldPrepared = new HashMap<>();

    PaperDistributionCancellationGate(int perCampaignHoldLimit) {
        if (perCampaignHoldLimit < 1) {
            throw new IllegalArgumentException("perCampaignHoldLimit must be positive");
        }
        this.perCampaignHoldLimit = perCampaignHoldLimit;
    }

    void begin(UUID campaignId) {
        fences.add(Objects.requireNonNull(campaignId, "campaignId"));
    }

    void committed(
            UUID campaignId,
            Consumer<CampaignRecipient> cancelClaim,
            Consumer<PreparedDistributionDelivery> cancelPrepared) {
        UUID id = Objects.requireNonNull(campaignId, "campaignId");
        fences.add(id);
        committed.add(id);
        drain(id, cancelClaim, cancelPrepared);
    }

    void release(
            UUID campaignId,
            Consumer<CampaignRecipient> processClaim,
            Consumer<PreparedDistributionDelivery> processPrepared) {
        UUID id = Objects.requireNonNull(campaignId, "campaignId");
        fences.remove(id);
        committed.remove(id);
        drain(id, processClaim, processPrepared);
    }

    Decision intercept(CampaignRecipient recipient) {
        Objects.requireNonNull(recipient, "recipient");
        UUID campaignId = recipient.campaignId();
        if (!fences.contains(campaignId)) {
            return Decision.PROCESS;
        }
        if (committed.contains(campaignId)) {
            return Decision.CANCEL;
        }
        return hold(heldClaims, campaignId, recipient)
                ? Decision.HELD
                : Decision.LEASE_EXPIRY_FALLBACK;
    }

    Decision intercept(PreparedDistributionDelivery delivery) {
        Objects.requireNonNull(delivery, "delivery");
        UUID campaignId = delivery.campaignId();
        if (!fences.contains(campaignId)) {
            return Decision.PROCESS;
        }
        if (committed.contains(campaignId)) {
            return Decision.CANCEL;
        }
        return hold(heldPrepared, campaignId, delivery)
                ? Decision.HELD
                : Decision.LEASE_EXPIRY_FALLBACK;
    }

    private <T> boolean hold(Map<UUID, List<T>> held, UUID campaignId, T item) {
        List<T> items = held.computeIfAbsent(campaignId, ignored -> new ArrayList<>());
        if (items.size() >= perCampaignHoldLimit) {
            return false;
        }
        items.add(item);
        return true;
    }

    private void drain(
            UUID campaignId,
            Consumer<CampaignRecipient> claimAction,
            Consumer<PreparedDistributionDelivery> preparedAction) {
        Objects.requireNonNull(claimAction, "claimAction");
        Objects.requireNonNull(preparedAction, "preparedAction");
        List<CampaignRecipient> claims = heldClaims.remove(campaignId);
        if (claims != null) {
            claims.forEach(claimAction);
        }
        List<PreparedDistributionDelivery> prepared = heldPrepared.remove(campaignId);
        if (prepared != null) {
            prepared.forEach(preparedAction);
        }
    }

    @Override
    public void close() {
        heldClaims.clear();
        heldPrepared.clear();
        fences.clear();
        committed.clear();
    }

    enum Decision {
        PROCESS,
        CANCEL,
        HELD,
        LEASE_EXPIRY_FALLBACK
    }
}
