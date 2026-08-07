package net.enthusia.loreitems.paper;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.DistributionCampaignState;

public record DistributionMarkerReconciliationPage(
        List<Entry> entries,
        PageRequest nextPage) {
    public DistributionMarkerReconciliationPage {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    public record Entry(
            UUID campaignId,
            DistributionCampaignState campaignState,
            Status status,
            Path markerPath,
            String detail) {
        public Entry {
            Objects.requireNonNull(campaignId, "campaignId");
            Objects.requireNonNull(campaignState, "campaignState");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            detail = detail.strip();
            if (detail.isEmpty()) {
                throw new IllegalArgumentException("detail must not be blank");
            }
            if ((status == Status.RECONCILED) != (markerPath != null)) {
                throw new IllegalArgumentException("Only reconciled entries contain a verified marker path");
            }
        }
    }

    public enum Status {
        RECONCILED,
        MISSING_SOURCE,
        FAILED,
        DRAFT_SKIPPED
    }
}
