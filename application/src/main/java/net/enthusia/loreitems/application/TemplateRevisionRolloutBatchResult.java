package net.enthusia.loreitems.application;

import java.util.Objects;

public record TemplateRevisionRolloutBatchResult(
        TemplateRevisionRolloutBatchStatus status,
        int scheduledCount,
        boolean hasMore) {
    public TemplateRevisionRolloutBatchResult {
        Objects.requireNonNull(status, "status");
        if (scheduledCount < 0) {
            throw new IllegalArgumentException("scheduledCount must not be negative");
        }
        validateState(status, scheduledCount, hasMore);
    }

    public static TemplateRevisionRolloutBatchResult scheduled(
            int scheduledCount, boolean hasMore) {
        return hasMore
                ? new TemplateRevisionRolloutBatchResult(
                        TemplateRevisionRolloutBatchStatus.SCHEDULED,
                        scheduledCount,
                        true)
                : complete(scheduledCount);
    }

    public static TemplateRevisionRolloutBatchResult complete(int scheduledCount) {
        return new TemplateRevisionRolloutBatchResult(
                TemplateRevisionRolloutBatchStatus.COMPLETE,
                scheduledCount,
                false);
    }

    public static TemplateRevisionRolloutBatchResult rejected(
            TemplateRevisionRolloutBatchStatus status) {
        if (status == TemplateRevisionRolloutBatchStatus.SCHEDULED
                || status == TemplateRevisionRolloutBatchStatus.COMPLETE) {
            throw new IllegalArgumentException("A successful batch status cannot be rejected");
        }
        return new TemplateRevisionRolloutBatchResult(status, 0, false);
    }

    private static void validateState(
            TemplateRevisionRolloutBatchStatus status,
            int scheduledCount,
            boolean hasMore) {
        switch (status) {
            case SCHEDULED -> validateScheduled(scheduledCount, hasMore);
            case COMPLETE -> validateComplete(hasMore);
            case DEFINITION_NOT_FOUND, DEFINITION_DELETED, STALE_REVISION ->
                    validateRejected(scheduledCount, hasMore);
            default -> throw new IllegalStateException(
                    "Unhandled rollout batch status: " + status);
        }
    }

    private static void validateScheduled(int scheduledCount, boolean hasMore) {
        if (scheduledCount < 1 || !hasMore) {
            throw new IllegalArgumentException(
                    "A scheduled batch must contain work and leave more work");
        }
    }

    private static void validateComplete(boolean hasMore) {
        if (hasMore) {
            throw new IllegalArgumentException(
                    "A completed rollout must not report more work");
        }
    }

    private static void validateRejected(int scheduledCount, boolean hasMore) {
        if (scheduledCount != 0 || hasMore) {
            throw new IllegalArgumentException(
                    "A rejected batch must not report scheduled work");
        }
    }
}
