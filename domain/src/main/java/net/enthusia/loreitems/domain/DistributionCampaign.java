package net.enthusia.loreitems.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record DistributionCampaign(
        UUID campaignId,
        String sourceFingerprint,
        String sourceName,
        String displayName,
        LoreDefinitionId definitionId,
        DistributionCampaignState state,
        long createdAtEpochMillis,
        long updatedAtEpochMillis,
        Long terminalAtEpochMillis) {
    public static final int MAX_SOURCE_FINGERPRINT_LENGTH = 256;
    public static final int MAX_SOURCE_NAME_LENGTH = 256;
    public static final int MAX_DISPLAY_NAME_LENGTH = 256;

    public DistributionCampaign {
        Objects.requireNonNull(campaignId, "campaignId");
        sourceFingerprint = normalizeSourceFingerprint(sourceFingerprint);
        sourceName = normalizeRequired(sourceName, "sourceName", MAX_SOURCE_NAME_LENGTH);
        displayName = normalizeRequired(displayName, "displayName", MAX_DISPLAY_NAME_LENGTH);
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(state, "state");
        if (createdAtEpochMillis < 0L || updatedAtEpochMillis < createdAtEpochMillis) {
            throw new IllegalArgumentException("Invalid campaign timestamps");
        }
        if (state.terminal() != (terminalAtEpochMillis != null)) {
            throw new IllegalArgumentException(
                    "Terminal campaign state and terminal timestamp must agree");
        }
        if (terminalAtEpochMillis != null
                && terminalAtEpochMillis < updatedAtEpochMillis) {
            throw new IllegalArgumentException(
                    "Campaign terminal timestamp must not precede its update timestamp");
        }
    }

    public static String normalizeSourceFingerprint(String sourceFingerprint) {
        String normalized = normalizeRequired(
                sourceFingerprint,
                "sourceFingerprint",
                MAX_SOURCE_FINGERPRINT_LENGTH).toLowerCase(Locale.ROOT);
        if (normalized.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Source fingerprint must not contain whitespace");
        }
        return normalized;
    }

    private static String normalizeRequired(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return normalized;
    }
}
