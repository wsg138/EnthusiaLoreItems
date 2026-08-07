package net.enthusia.loreitems.domain;

import java.util.Objects;
import java.util.UUID;

public record DistributionCampaign(
        UUID campaignId,
        String sourceFingerprint,
        String sourceName,
        String displayName,
        LoreDefinitionId definitionId,
        TemplateRevision definitionRevision,
        DistributionCampaignState state,
        long createdAtEpochMillis,
        long updatedAtEpochMillis,
        Long terminalAtEpochMillis) {
    private static final long MIN_TIMESTAMP = 0L;
    private static final int MAX_SOURCE_FINGERPRINT_LENGTH = 256;
    private static final int MAX_TEXT_LENGTH = 256;

    public DistributionCampaign {
        Objects.requireNonNull(campaignId, "campaignId");
        sourceFingerprint = normalizeSourceFingerprint(sourceFingerprint);
        sourceName = normalizeText(sourceName, "sourceName");
        displayName = normalizeText(displayName, "displayName");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(definitionRevision, "definitionRevision");
        Objects.requireNonNull(state, "state");
        if (createdAtEpochMillis < MIN_TIMESTAMP || updatedAtEpochMillis < createdAtEpochMillis) {
            throw new IllegalArgumentException("Invalid campaign timestamps");
        }
        if (terminalAtEpochMillis != null && terminalAtEpochMillis < updatedAtEpochMillis) {
            throw new IllegalArgumentException("terminalAtEpochMillis must not precede update time");
        }
        if (state.terminal() != (terminalAtEpochMillis != null)) {
            throw new IllegalArgumentException("Campaign terminal timestamp does not match state");
        }
    }

    /**
     * Compatibility constructor for the pre-WP-03 foundation API. New campaign starts must pass
     * the selected revision explicitly.
     */
    public DistributionCampaign(
            UUID campaignId,
            String sourceFingerprint,
            String sourceName,
            String displayName,
            LoreDefinitionId definitionId,
            DistributionCampaignState state,
            long createdAtEpochMillis,
            long updatedAtEpochMillis,
            Long terminalAtEpochMillis) {
        this(
                campaignId,
                sourceFingerprint,
                sourceName,
                displayName,
                definitionId,
                new TemplateRevision(1L),
                state,
                createdAtEpochMillis,
                updatedAtEpochMillis,
                terminalAtEpochMillis);
    }

    public static String normalizeSourceFingerprint(String sourceFingerprint) {
        Objects.requireNonNull(sourceFingerprint, "sourceFingerprint");
        String normalized = sourceFingerprint.strip().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > MAX_SOURCE_FINGERPRINT_LENGTH) {
            throw new IllegalArgumentException("Invalid sourceFingerprint");
        }
        return normalized;
    }

    private static String normalizeText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return normalized;
    }
}
