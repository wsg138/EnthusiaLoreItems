package net.enthusia.loreitems.domain;

import java.util.Objects;
import java.util.UUID;

public record CampaignRecipient(
        UUID campaignId,
        CampaignRecipientKey recipientKey,
        int snapshotIndex,
        String originalValue,
        UUID playerId,
        CampaignRecipientState state,
        LoreInstanceId instanceId,
        String claimToken,
        Long claimExpiresAtEpochMillis,
        int attemptCount,
        Long nextAttemptAtEpochMillis,
        Long deliveredAtEpochMillis,
        long updatedAtEpochMillis) {
    public static final int MAX_CLAIM_TOKEN_LENGTH = 200;

    public CampaignRecipient {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(recipientKey, "recipientKey");
        if (snapshotIndex < 0) {
            throw new IllegalArgumentException("snapshotIndex must not be negative");
        }
        originalValue = CampaignRecipientKey.normalizeOriginalValue(originalValue);
        Objects.requireNonNull(state, "state");
        if (attemptCount < 0 || updatedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("Invalid recipient counters or timestamps");
        }
        if ((claimToken == null) != (claimExpiresAtEpochMillis == null)) {
            throw new IllegalArgumentException(
                    "Claim token and expiry must be present together");
        }
        if (claimToken != null) {
            claimToken = claimToken.strip();
            if (claimToken.isEmpty() || claimToken.length() > MAX_CLAIM_TOKEN_LENGTH) {
                throw new IllegalArgumentException("Invalid claim token");
            }
            if (claimExpiresAtEpochMillis < updatedAtEpochMillis) {
                throw new IllegalArgumentException("Claim expiry must not precede the update");
            }
        }
        if (nextAttemptAtEpochMillis != null && nextAttemptAtEpochMillis < 0L) {
            throw new IllegalArgumentException("nextAttemptAt must not be negative");
        }
        if (deliveredAtEpochMillis != null
                && (deliveredAtEpochMillis < 0L
                        || deliveredAtEpochMillis > updatedAtEpochMillis)) {
            throw new IllegalArgumentException("Invalid deliveredAt timestamp");
        }
        validateIdentity();
        validateStateMetadata();
    }

    public static CampaignRecipient unresolvedName(
            UUID campaignId, int snapshotIndex, String originalValue, long createdAtEpochMillis) {
        return new CampaignRecipient(
                campaignId,
                CampaignRecipientKey.forUnresolvedName(originalValue),
                snapshotIndex,
                originalValue,
                null,
                CampaignRecipientState.PENDING_NAME,
                null,
                null,
                null,
                0,
                null,
                null,
                createdAtEpochMillis);
    }

    public static CampaignRecipient knownPlayer(
            UUID campaignId,
            int snapshotIndex,
            UUID playerId,
            String originalValue,
            long createdAtEpochMillis) {
        return new CampaignRecipient(
                campaignId,
                CampaignRecipientKey.forPlayer(playerId),
                snapshotIndex,
                originalValue,
                playerId,
                CampaignRecipientState.PENDING_OFFLINE,
                null,
                null,
                null,
                0,
                null,
                null,
                createdAtEpochMillis);
    }

    private void validateIdentity() {
        if (recipientKey.playerUuidKey()) {
            if (playerId == null || !recipientKey.playerUuid().equals(playerId)) {
                throw new IllegalArgumentException(
                        "UUID recipient key must match the authoritative player UUID");
            }
        } else if (playerId == null
                && !recipientKey.equals(CampaignRecipientKey.forUnresolvedName(originalValue))) {
            throw new IllegalArgumentException(
                    "Unbound name recipient key must match the original value case-insensitively");
        }
    }

    private void validateStateMetadata() {
        switch (state) {
            case PENDING_NAME -> {
                if (!recipientKey.unresolvedNameKey() || playerId != null) {
                    throw new IllegalArgumentException(
                            "PENDING_NAME requires an unresolved name and no player UUID");
                }
                requireNoInstanceOrDelivery();
                requireNoClaim();
                if (nextAttemptAtEpochMillis != null) {
                    throw new IllegalArgumentException(
                            "PENDING_NAME must not have a delivery retry timestamp");
                }
            }
            case PENDING_OFFLINE, PENDING_SPACE -> {
                requirePlayer();
                requireNoInstanceOrDelivery();
                requireNoClaim();
            }
            case RESERVED -> {
                requirePlayer();
                if (claimToken == null || claimExpiresAtEpochMillis == null) {
                    throw new IllegalArgumentException("RESERVED recipient requires a live claim");
                }
                if (deliveredAtEpochMillis != null || nextAttemptAtEpochMillis != null) {
                    throw new IllegalArgumentException(
                            "RESERVED recipient must not be delivered or retry-scheduled");
                }
            }
            case DELIVERED -> {
                requirePlayer();
                if (instanceId == null || deliveredAtEpochMillis == null) {
                    throw new IllegalArgumentException(
                            "DELIVERED recipient requires an instance and delivery timestamp");
                }
                requireNoClaim();
                if (nextAttemptAtEpochMillis != null) {
                    throw new IllegalArgumentException(
                            "DELIVERED recipient must not have a retry timestamp");
                }
            }
            case CANCELLED -> {
                requireNoInstanceOrDelivery();
                requireNoClaim();
                if (nextAttemptAtEpochMillis != null) {
                    throw new IllegalArgumentException(
                            "CANCELLED recipient must not have a retry timestamp");
                }
            }
            case REVIEW_REQUIRED -> {
                requireNoClaim();
                if (deliveredAtEpochMillis != null || nextAttemptAtEpochMillis != null) {
                    throw new IllegalArgumentException(
                            "REVIEW_REQUIRED recipient must not claim delivery or retry state");
                }
            }
            default -> throw new IllegalStateException("Unhandled recipient state");
        }
    }

    private void requirePlayer() {
        if (playerId == null) {
            throw new IllegalArgumentException(state + " recipient requires a player UUID");
        }
    }

    private void requireNoInstanceOrDelivery() {
        if (instanceId != null || deliveredAtEpochMillis != null) {
            throw new IllegalArgumentException(
                    state + " recipient must not contain delivered instance metadata");
        }
    }

    private void requireNoClaim() {
        if (claimToken != null || claimExpiresAtEpochMillis != null) {
            throw new IllegalArgumentException(state + " recipient must not contain claim metadata");
        }
    }
}
