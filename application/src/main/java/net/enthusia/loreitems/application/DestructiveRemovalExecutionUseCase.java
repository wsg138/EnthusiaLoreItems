package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.DestructiveEffectState;
import net.enthusia.loreitems.domain.DestructiveOperationType;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;

public interface DestructiveRemovalExecutionUseCase {
    CompletionStage<PrepareResult> prepare(Observation observation);

    CompletionStage<Boolean> release(PreparedRemoval removal, String reason);

    CompletionStage<Boolean> complete(PreparedRemoval removal, String beforeFingerprint);

    CompletionStage<Boolean> requireReview(
            PreparedRemoval removal,
            DestructiveEffectState effectState,
            String beforeFingerprint,
            String afterFingerprint,
            String detail);

    record Observation(
            LoreItemIdentity identity,
            String locationType,
            String locationKey,
            String containerPath,
            String fingerprint) {
        public static final int MAX_LOCATION_LENGTH = 1_000;
        public static final int MAX_FINGERPRINT_LENGTH = 512;

        public Observation {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(locationType, "locationType");
            Objects.requireNonNull(locationKey, "locationKey");
            Objects.requireNonNull(fingerprint, "fingerprint");
            locationType = locationType.strip();
            locationKey = locationKey.strip();
            containerPath = normalizeNullable(containerPath);
            fingerprint = fingerprint.strip();
            if (locationType.isEmpty() || locationType.length() > MAX_LOCATION_LENGTH
                    || locationKey.isEmpty() || locationKey.length() > MAX_LOCATION_LENGTH
                    || (containerPath != null && containerPath.length() > MAX_LOCATION_LENGTH)) {
                throw new IllegalArgumentException("Invalid destructive-removal location evidence");
            }
            if (fingerprint.isEmpty() || fingerprint.length() > MAX_FINGERPRINT_LENGTH) {
                throw new IllegalArgumentException("Invalid destructive-removal fingerprint");
            }
        }

        private static String normalizeNullable(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.strip();
            return normalized.isEmpty() ? null : normalized;
        }
    }

    record PreparedRemoval(
            UUID operationId,
            DestructiveOperationType operationType,
            LoreDefinitionId definitionId,
            LoreInstanceId instanceId,
            TemplateRevision expectedAppliedRevision,
            LoreItemIdentity observedIdentity,
            String locationType,
            String locationKey,
            String containerPath,
            String beforeFingerprint,
            String claimToken,
            long claimExpiresAtEpochMillis) {
        public PreparedRemoval {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(operationType, "operationType");
            Objects.requireNonNull(definitionId, "definitionId");
            Objects.requireNonNull(instanceId, "instanceId");
            Objects.requireNonNull(expectedAppliedRevision, "expectedAppliedRevision");
            Objects.requireNonNull(observedIdentity, "observedIdentity");
            Objects.requireNonNull(locationType, "locationType");
            Objects.requireNonNull(locationKey, "locationKey");
            Objects.requireNonNull(beforeFingerprint, "beforeFingerprint");
            Objects.requireNonNull(claimToken, "claimToken");
            if (!definitionId.equals(observedIdentity.definitionId())
                    || !instanceId.equals(observedIdentity.instanceId())) {
                throw new IllegalArgumentException(
                        "Prepared removal identity does not match the target");
            }
            if (claimToken.isBlank() || claimExpiresAtEpochMillis < 0L) {
                throw new IllegalArgumentException("Invalid destructive-removal claim");
            }
        }
    }

    record PrepareResult(Status status, PreparedRemoval preparedRemoval, String detail) {
        public PrepareResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            detail = detail.strip();
            if (detail.isEmpty()) {
                throw new IllegalArgumentException("Destructive prepare detail must not be blank");
            }
            if ((status == Status.PREPARED) != (preparedRemoval != null)) {
                throw new IllegalArgumentException(
                        "Only PREPARED destructive results contain a claim");
            }
        }

        public static PrepareResult prepared(PreparedRemoval removal) {
            return new PrepareResult(
                    Status.PREPARED,
                    Objects.requireNonNull(removal, "removal"),
                    "The naturally encountered item was durably claimed for removal.");
        }

        public static PrepareResult noPendingWork() {
            return new PrepareResult(
                    Status.NO_PENDING_WORK,
                    null,
                    "No active destructive target applies to the encountered item.");
        }

        public static PrepareResult reviewRequired(String detail) {
            return new PrepareResult(Status.REVIEW_REQUIRED, null, detail);
        }
    }

    enum Status {
        PREPARED,
        NO_PENDING_WORK,
        REVIEW_REQUIRED
    }
}