package net.enthusia.loreitems.application;

import java.util.Objects;

public record TemplateUpdatePrepareResult(
        Status status,
        PreparedTemplateUpdate preparedUpdate,
        String detail) {
    public TemplateUpdatePrepareResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
        if ((status == Status.PREPARED) != (preparedUpdate != null)) {
            throw new IllegalArgumentException("Only PREPARED results contain an update");
        }
    }

    public static TemplateUpdatePrepareResult prepared(PreparedTemplateUpdate update) {
        return new TemplateUpdatePrepareResult(
                Status.PREPARED,
                Objects.requireNonNull(update, "update"),
                "The encountered lore item was claimed for a bounded template update.");
    }

    public static TemplateUpdatePrepareResult noPendingWork() {
        return new TemplateUpdatePrepareResult(
                Status.NO_PENDING_WORK,
                null,
                "No due template update exists for the encountered lore item.");
    }

    public static TemplateUpdatePrepareResult reviewRequired(String detail) {
        return new TemplateUpdatePrepareResult(Status.REVIEW_REQUIRED, null, detail);
    }

    public enum Status {
        PREPARED,
        NO_PENDING_WORK,
        REVIEW_REQUIRED
    }
}
