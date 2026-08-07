package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.DestructiveEffectState;
import net.enthusia.loreitems.domain.DestructiveOperationState;
import net.enthusia.loreitems.domain.DestructiveOperationType;
import net.enthusia.loreitems.domain.DestructiveTargetState;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;

public interface DestructiveAdministrationUseCase {
    CompletionStage<Optional<Preview>> preview(PreviewRequest request);

    CompletionStage<StartResult> start(StartRequest request);

    CompletionStage<Page<OperationView>> listOperations(PageRequest request);

    CompletionStage<Page<TargetView>> listTargets(UUID operationId, PageRequest request);

    CompletionStage<ControlResult> pause(ControlRequest request);

    CompletionStage<ControlResult> resume(ControlRequest request);

    CompletionStage<ReviewResult> resolveReview(ReviewRequest request);

    CompletionStage<Metrics> metrics();

    record PreviewRequest(
            DestructiveOperationType operationType,
            LoreDefinitionId definitionId,
            LoreInstanceId exactInstanceId) {
        public PreviewRequest {
            Objects.requireNonNull(operationType, "operationType");
            definitionId = requireDefinitionId(definitionId);
            if (operationType.exactInstanceRequired() != (exactInstanceId != null)) {
                throw new IllegalArgumentException(
                        "Exact-instance identity must match the destructive operation type");
            }
        }
    }

    record Preview(
            DestructiveOperationType operationType,
            LoreDefinitionId definitionId,
            DefinitionKey lookupKey,
            String displayName,
            TemplateRevision expectedRevision,
            LoreInstanceId exactInstanceId,
            long targetCount,
            long inaccessibleCount,
            long queuedCount,
            long anomalyCount,
            String confirmationToken) {
        public static final int MAX_CONFIRMATION_TOKEN_LENGTH = 128;

        public Preview {
            Objects.requireNonNull(operationType, "operationType");
            definitionId = requireDefinitionId(definitionId);
            Objects.requireNonNull(lookupKey, "lookupKey");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(expectedRevision, "expectedRevision");
            Objects.requireNonNull(confirmationToken, "confirmationToken");
            displayName = displayName.strip();
            confirmationToken = confirmationToken.strip();
            if (operationType.exactInstanceRequired() != (exactInstanceId != null)) {
                throw new IllegalArgumentException(
                        "Exact-instance identity must match the destructive operation type");
            }
            if (displayName.isEmpty() || displayName.length() > 256) {
                throw new IllegalArgumentException("Invalid destructive preview display name");
            }
            if (targetCount < 0L || inaccessibleCount < 0L
                    || queuedCount < 0L || anomalyCount < 0L) {
                throw new IllegalArgumentException("Destructive preview counts must not be negative");
            }
            if (confirmationToken.isEmpty()
                    || confirmationToken.length() > MAX_CONFIRMATION_TOKEN_LENGTH) {
                throw new IllegalArgumentException("Invalid destructive confirmation token");
            }
        }
    }

    record StartRequest(Preview preview, String actorId, String idempotencyKey) {
        public static final int MAX_ACTOR_LENGTH = 200;
        public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 240;

        public StartRequest {
            Objects.requireNonNull(preview, "preview");
            actorId = requireActorId(actorId).strip();
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            idempotencyKey = idempotencyKey.strip();
            if (actorId.isEmpty() || actorId.length() > MAX_ACTOR_LENGTH) {
                throw new IllegalArgumentException("Invalid destructive-operation actor");
            }
            if (idempotencyKey.isEmpty()
                    || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
                throw new IllegalArgumentException("Invalid destructive idempotency key");
            }
        }
    }

    record StartResult(StartStatus status, OperationView operation, String detail) {
        public StartResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            detail = detail.strip();
            if (detail.isEmpty()) {
                throw new IllegalArgumentException("Destructive start detail must not be blank");
            }
            boolean successful = status == StartStatus.STARTED
                    || status == StartStatus.ALREADY_ACCEPTED;
            if (successful != (operation != null)) {
                throw new IllegalArgumentException(
                        "Only successful destructive starts contain an operation");
            }
        }

        public static StartResult success(StartStatus status, OperationView operation, String detail) {
            return new StartResult(status, Objects.requireNonNull(operation, "operation"), detail);
        }

        public static StartResult failure(StartStatus status, String detail) {
            return new StartResult(status, null, detail);
        }
    }

    enum StartStatus {
        STARTED,
        ALREADY_ACCEPTED,
        STALE_CONFIRMATION,
        NOT_FOUND,
        ALREADY_DELETED,
        TARGET_CONFLICT,
        REJECTED
    }

    record ControlRequest(UUID operationId, String actorId) {
        public ControlRequest {
            operationId = requireOperationId(operationId);
            actorId = requireActorId(actorId).strip();
            if (actorId.isEmpty() || actorId.length() > StartRequest.MAX_ACTOR_LENGTH) {
                throw new IllegalArgumentException("Invalid destructive-operation actor");
            }
        }
    }

    record ControlResult(ControlStatus status, OperationView operation, String detail) {
        public ControlResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            detail = detail.strip();
            if (detail.isEmpty()) {
                throw new IllegalArgumentException("Destructive control detail must not be blank");
            }
            if ((status == ControlStatus.UPDATED || status == ControlStatus.ALREADY_IN_STATE)
                    != (operation != null)) {
                throw new IllegalArgumentException(
                        "Only successful destructive controls contain an operation");
            }
        }
    }

    enum ControlStatus {
        UPDATED,
        ALREADY_IN_STATE,
        NOT_FOUND,
        TERMINAL,
        REJECTED
    }

    record ReviewRequest(
            UUID operationId,
            LoreInstanceId instanceId,
            ReviewResolution resolution,
            String actorId,
            String evidenceDetail) {
        public static final int MAX_EVIDENCE_DETAIL_LENGTH = 2_000;

        public ReviewRequest {
            operationId = requireOperationId(operationId);
            Objects.requireNonNull(instanceId, "instanceId");
            Objects.requireNonNull(resolution, "resolution");
            actorId = requireActorId(actorId).strip();
            Objects.requireNonNull(evidenceDetail, "evidenceDetail");
            evidenceDetail = evidenceDetail.strip();
            if (actorId.isEmpty() || actorId.length() > StartRequest.MAX_ACTOR_LENGTH) {
                throw new IllegalArgumentException("Invalid destructive-review actor");
            }
            if (evidenceDetail.isEmpty()
                    || evidenceDetail.length() > MAX_EVIDENCE_DETAIL_LENGTH) {
                throw new IllegalArgumentException("Invalid destructive-review evidence detail");
            }
        }
    }

    enum ReviewResolution {
        REQUEUE_NO_SIDE_EFFECT,
        MARK_VERIFIED_REMOVED,
        ABORT_NO_SIDE_EFFECT
    }

    record ReviewResult(ReviewStatus status, TargetView target, String detail) {
        public ReviewResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            detail = detail.strip();
            if (detail.isEmpty()) {
                throw new IllegalArgumentException("Destructive review detail must not be blank");
            }
            if ((status == ReviewStatus.RESOLVED) != (target != null)) {
                throw new IllegalArgumentException(
                        "Only resolved destructive reviews contain a target");
            }
        }
    }

    enum ReviewStatus {
        RESOLVED,
        NOT_FOUND,
        NOT_REVIEW_REQUIRED,
        EVIDENCE_MISMATCH,
        REJECTED
    }

    record OperationView(
            UUID operationId,
            DestructiveOperationType operationType,
            LoreDefinitionId definitionId,
            LoreInstanceId exactInstanceId,
            TemplateRevision expectedRevision,
            DestructiveOperationState state,
            String actorId,
            String idempotencyKey,
            long targetCount,
            long pendingCount,
            long claimedCount,
            long reviewCount,
            long completedCount,
            long abortedCount,
            long acceptedAtEpochMillis,
            long updatedAtEpochMillis,
            Long terminalAtEpochMillis) {
        public OperationView {
            operationId = requireOperationId(operationId);
            Objects.requireNonNull(operationType, "operationType");
            definitionId = requireDefinitionId(definitionId);
            Objects.requireNonNull(expectedRevision, "expectedRevision");
            Objects.requireNonNull(state, "state");
            actorId = requireActorId(actorId);
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            if (operationType.exactInstanceRequired() != (exactInstanceId != null)) {
                throw new IllegalArgumentException(
                        "Exact-instance identity must match the destructive operation type");
            }
            if (targetCount < 0L || pendingCount < 0L || claimedCount < 0L
                    || reviewCount < 0L || completedCount < 0L || abortedCount < 0L) {
                throw new IllegalArgumentException("Destructive operation counts must not be negative");
            }
            if (abortedCount > targetCount || completedCount > targetCount - abortedCount) {
                throw new IllegalArgumentException(
                        "Destructive operation terminal counts exceed target count");
            }
            if (state.terminal() != (terminalAtEpochMillis != null)) {
                throw new IllegalArgumentException(
                        "Destructive operation terminal timestamp does not match state");
            }
        }

        public long remainingCount() {
            return targetCount - completedCount - abortedCount;
        }
    }

    record TargetView(
            UUID operationId,
            LoreInstanceId instanceId,
            LoreDefinitionId definitionId,
            TemplateRevision expectedAppliedRevision,
            String expectedLocationType,
            String expectedLocationKey,
            String expectedContainerPath,
            DestructiveTargetState state,
            DestructiveEffectState effectState,
            int attemptCount,
            Long claimExpiresAtEpochMillis,
            String beforeFingerprint,
            String afterFingerprint,
            String lastError,
            long createdAtEpochMillis,
            long updatedAtEpochMillis) {
        public TargetView {
            operationId = requireOperationId(operationId);
            Objects.requireNonNull(instanceId, "instanceId");
            definitionId = requireDefinitionId(definitionId);
            Objects.requireNonNull(expectedAppliedRevision, "expectedAppliedRevision");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(effectState, "effectState");
            if (attemptCount < 0) {
                throw new IllegalArgumentException("Destructive target attempts must not be negative");
            }
            if ((state == DestructiveTargetState.CLAIMED)
                    != (claimExpiresAtEpochMillis != null)) {
                throw new IllegalArgumentException(
                        "Destructive target claim expiry does not match state");
            }
        }
    }

    record Metrics(
            long activeOperations,
            long pausedOperations,
            long queuedTargets,
            long activeLeases,
            long reviewRequiredTargets,
            long oldestQueuedAgeMillis,
            long totalAttempts) {
        public Metrics {
            if (activeOperations < 0L || pausedOperations < 0L || queuedTargets < 0L
                    || activeLeases < 0L || reviewRequiredTargets < 0L
                    || oldestQueuedAgeMillis < 0L || totalAttempts < 0L) {
                throw new IllegalArgumentException("Destructive metrics must not be negative");
            }
        }
    }

    private static LoreDefinitionId requireDefinitionId(LoreDefinitionId definitionId) {
        return Objects.requireNonNull(definitionId, "definitionId");
    }

    private static UUID requireOperationId(UUID operationId) {
        return Objects.requireNonNull(operationId, "operationId");
    }

    private static String requireActorId(String actorId) {
        return Objects.requireNonNull(actorId, "actorId");
    }
}
