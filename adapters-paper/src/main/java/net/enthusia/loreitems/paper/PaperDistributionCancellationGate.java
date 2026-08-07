package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.enthusia.loreitems.application.PreparedDistributionDelivery;
import net.enthusia.loreitems.domain.CampaignRecipient;

final class PaperDistributionCancellationGate implements AutoCloseable {
    private static final int MIN_HOLD_LIMIT = 1;

    private final int perCampaignHoldLimit;
    private final Set<UUID> fences = ConcurrentHashMap.newKeySet();
    private final Set<UUID> committedCampaigns = ConcurrentHashMap.newKeySet();
    private final Map<UUID, List<CampaignRecipient>> heldClaims = new ConcurrentHashMap<>();
    private final Map<UUID, List<PreparedDistributionDelivery>> heldPrepared = new ConcurrentHashMap<>();

    PaperDistributionCancellationGate(int perCampaignHoldLimit) {
        if (perCampaignHoldLimit < MIN_HOLD_LIMIT) {
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
        committedCampaigns.add(id);
        drain(id, cancelClaim, cancelPrepared);
    }

    void release(
            UUID campaignId,
            Consumer<CampaignRecipient> processClaim,
            Consumer<PreparedDistributionDelivery> processPrepared) {
        UUID id = Objects.requireNonNull(campaignId, "campaignId");
        fences.remove(id);
        committedCampaigns.remove(id);
        drain(id, processClaim, processPrepared);
    }

    Decision intercept(CampaignRecipient recipient) {
        Objects.requireNonNull(recipient, "recipient");
        UUID campaignId = recipient.campaignId();
        if (!fences.contains(campaignId)) {
            return Decision.PROCESS;
        }
        if (committedCampaigns.contains(campaignId)) {
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
        if (committedCampaigns.contains(campaignId)) {
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
        committedCampaigns.clear();
    }

    enum Decision {
        PROCESS,
        CANCEL,
        HELD,
        LEASE_EXPIRY_FALLBACK
    }
}
