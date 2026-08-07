package net.enthusia.loreitems.paper;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

public record DistributionCampaignConfirmationResult(
        Status status,
        UUID campaignId,
        Path markerPath,
        String detail) {
    public DistributionCampaignConfirmationResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(detail, "detail");
        detail = detail.strip();
        if (detail.isEmpty()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
        if ((status == Status.STARTED) != (markerPath != null)) {
            throw new IllegalArgumentException("Only a fully started campaign has a confirmed marker path");
        }
    }

    public static DistributionCampaignConfirmationResult started(UUID campaignId, Path markerPath) {
        return new DistributionCampaignConfirmationResult(
                Status.STARTED,
                campaignId,
                Objects.requireNonNull(markerPath, "markerPath"),
                "Campaign snapshot committed and the source was moved to its active marker.");
    }

    public static DistributionCampaignConfirmationResult sourceAlreadyUsed(UUID campaignId) {
        return new DistributionCampaignConfirmationResult(
                Status.SOURCE_ALREADY_USED,
                campaignId,
                null,
                "This exact source fingerprint already belongs to a durable campaign.");
    }

    public static DistributionCampaignConfirmationResult sourceChanged(UUID campaignId) {
        return new DistributionCampaignConfirmationResult(
                Status.SOURCE_CHANGED,
                campaignId,
                null,
                "The group source changed or disappeared after preview; no campaign was started.");
    }

    public static DistributionCampaignConfirmationResult markerRepairRequired(
            UUID campaignId, String detail) {
        return new DistributionCampaignConfirmationResult(
                Status.STARTED_MARKER_REPAIR_REQUIRED,
                campaignId,
                null,
                detail);
    }

    public enum Status {
        STARTED,
        SOURCE_ALREADY_USED,
        SOURCE_CHANGED,
        STARTED_MARKER_REPAIR_REQUIRED
    }
}
