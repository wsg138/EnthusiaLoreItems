package net.enthusia.loreitems.application;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientKey;
import net.enthusia.loreitems.domain.CampaignRecipientState;
import net.enthusia.loreitems.domain.DistributionCampaign;
import net.enthusia.loreitems.domain.DistributionCampaignState;

public record DistributionCampaignStartRequest(
        DistributionCampaign campaign,
        List<CampaignRecipient> recipients,
        String actorType,
        String actorId) {
    public static final int MAX_RECIPIENTS = 100_000;

    public DistributionCampaignStartRequest {
        Objects.requireNonNull(campaign, "campaign");
        if (campaign.state() != DistributionCampaignState.DRAFT
                || campaign.terminalAtEpochMillis() != null) {
            throw new IllegalArgumentException("Campaign start request must contain a DRAFT campaign");
        }
        recipients = List.copyOf(Objects.requireNonNull(recipients, "recipients"));
        if (recipients.isEmpty() || recipients.size() > MAX_RECIPIENTS) {
            throw new IllegalArgumentException(
                    "Campaign recipient count must be between 1 and " + MAX_RECIPIENTS);
        }
        validateRecipients(campaign, recipients);
        actorType = normalizeRequired(actorType, "actorType", AuditEventRecord.MAX_TYPE_LENGTH);
        if (actorId != null) {
            actorId = normalizeRequired(actorId, "actorId", AuditEventRecord.MAX_ID_LENGTH);
        }
    }

    private static void validateRecipients(
            DistributionCampaign campaign, List<CampaignRecipient> recipients) {
        Set<CampaignRecipientKey> keys = new HashSet<>();
        Set<UUID> playerIds = new HashSet<>();
        for (int index = 0; index < recipients.size(); index++) {
            CampaignRecipient recipient = Objects.requireNonNull(recipients.get(index), "recipient");
            validateRecipient(campaign, recipient, index);
            requireUniqueKey(keys, recipient);
            requireUniquePlayerId(playerIds, recipient);
        }
    }

    private static void validateRecipient(
            DistributionCampaign campaign, CampaignRecipient recipient, int index) {
        if (!recipient.campaignId().equals(campaign.campaignId())) {
            throw new IllegalArgumentException("Recipient belongs to another campaign");
        }
        if (recipient.snapshotIndex() != index) {
            throw new IllegalArgumentException("Recipient snapshot indexes must be contiguous from zero");
        }
        if (recipient.state() != CampaignRecipientState.UNRESOLVED
                && recipient.state() != CampaignRecipientState.QUEUED_OFFLINE) {
            throw new IllegalArgumentException("Recipient snapshot contains mutable delivery state");
        }
    }

    private static void requireUniqueKey(
            Set<CampaignRecipientKey> keys, CampaignRecipient recipient) {
        if (!keys.add(recipient.recipientKey())) {
            throw new IllegalArgumentException("Recipient snapshot contains duplicate normalized keys");
        }
    }

    private static void requireUniquePlayerId(Set<UUID> playerIds, CampaignRecipient recipient) {
        UUID playerId = recipient.playerId();
        if (playerId != null && !playerIds.add(playerId)) {
            throw new IllegalArgumentException("Recipient snapshot contains duplicate player UUIDs");
        }
    }

    private static String normalizeRequired(String value, String name, int maxLength) {
        Objects.requireNonNull(value, name);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return normalized;
    }
}
