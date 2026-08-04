package net.enthusia.loreitems.application;

import java.util.Objects;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;

public record TemplateRevisionStartResult(
        TemplateRevisionStartStatus status,
        LoreDefinitionId definitionId,
        TemplateRevision currentRevision,
        TemplateRevisionRolloutBatchResult initialBatch) {
    public TemplateRevisionStartResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(definitionId, "definitionId");
        switch (status) {
            case STARTED -> {
                Objects.requireNonNull(currentRevision, "currentRevision");
                Objects.requireNonNull(initialBatch, "initialBatch");
                requireSuccessfulInitialBatch(initialBatch);
            }
            case DEFINITION_NOT_FOUND -> {
                if (currentRevision != null || initialBatch != null) {
                    throw new IllegalArgumentException(
                            "A missing definition cannot expose rollout state");
                }
            }
            case DEFINITION_DELETED, REVISION_CONFLICT, ROLLOUT_IN_PROGRESS -> {
                Objects.requireNonNull(currentRevision, "currentRevision");
                if (initialBatch != null) {
                    throw new IllegalArgumentException(
                            "A rejected revision cannot expose an initial batch");
                }
            }
            default -> throw new IllegalStateException("Unhandled revision start status: " + status);
        }
    }

    public static TemplateRevisionStartResult started(
            LoreDefinitionId definitionId,
            TemplateRevision currentRevision,
            TemplateRevisionRolloutBatchResult initialBatch) {
        return new TemplateRevisionStartResult(
                TemplateRevisionStartStatus.STARTED,
                definitionId,
                currentRevision,
                initialBatch);
    }

    public static TemplateRevisionStartResult definitionNotFound(
            LoreDefinitionId definitionId) {
        return new TemplateRevisionStartResult(
                TemplateRevisionStartStatus.DEFINITION_NOT_FOUND,
                definitionId,
                null,
                null);
    }

    public static TemplateRevisionStartResult definitionDeleted(
            LoreDefinitionId definitionId, TemplateRevision currentRevision) {
        return new TemplateRevisionStartResult(
                TemplateRevisionStartStatus.DEFINITION_DELETED,
                definitionId,
                currentRevision,
                null);
    }

    public static TemplateRevisionStartResult revisionConflict(
            LoreDefinitionId definitionId, TemplateRevision currentRevision) {
        return new TemplateRevisionStartResult(
                TemplateRevisionStartStatus.REVISION_CONFLICT,
                definitionId,
                currentRevision,
                null);
    }

    public static TemplateRevisionStartResult rolloutInProgress(
            LoreDefinitionId definitionId, TemplateRevision currentRevision) {
        return new TemplateRevisionStartResult(
                TemplateRevisionStartStatus.ROLLOUT_IN_PROGRESS,
                definitionId,
                currentRevision,
                null);
    }

    private static void requireSuccessfulInitialBatch(
            TemplateRevisionRolloutBatchResult initialBatch) {
        if (initialBatch.status() != TemplateRevisionRolloutBatchStatus.SCHEDULED
                && initialBatch.status() != TemplateRevisionRolloutBatchStatus.COMPLETE) {
            throw new IllegalArgumentException(
                    "A started revision requires a successful initial batch");
        }
    }
}
