package net.enthusia.loreitems.domain;

import java.util.Objects;
import java.util.UUID;

public record InstanceAnomaly(
        UUID anomalyId,
        LoreInstanceId instanceId,
        LoreDefinitionId definitionId,
        Type type,
        Status status,
        String detail,
        long firstSeenAtEpochMillis,
        long lastSeenAtEpochMillis,
        Long acknowledgedAtEpochMillis,
        String acknowledgedBy,
        Long resolvedAtEpochMillis,
        String resolutionDetail,
        long stateRevision) {
    private static final long MIN_TIMESTAMP = 0L;
    private static final long MIN_STATE_REVISION = 0L;

    public static final int MAX_DETAIL_LENGTH = 65_536;
    public static final int MAX_ACTOR_LENGTH = 200;

    public InstanceAnomaly {
        Objects.requireNonNull(anomalyId, "anomalyId");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        detail = requireText(detail, "detail", MAX_DETAIL_LENGTH);
        if (firstSeenAtEpochMillis < MIN_TIMESTAMP
                || lastSeenAtEpochMillis < firstSeenAtEpochMillis) {
            throw new IllegalArgumentException("Invalid anomaly timestamps");
        }
        if (stateRevision < MIN_STATE_REVISION) {
            throw new IllegalArgumentException("stateRevision must not be negative");
        }
        if ((acknowledgedAtEpochMillis == null) != (acknowledgedBy == null)) {
            throw new IllegalArgumentException(
                    "Acknowledgement timestamp and actor must be present together");
        }
        if (acknowledgedBy != null) {
            acknowledgedBy = requireText(
                    acknowledgedBy, "acknowledgedBy", MAX_ACTOR_LENGTH);
        }
        if (resolutionDetail != null) {
            resolutionDetail = requireText(
                    resolutionDetail, "resolutionDetail", MAX_DETAIL_LENGTH);
        }
        validateStatusMetadata(
                status,
                acknowledgedAtEpochMillis,
                acknowledgedBy,
                resolvedAtEpochMillis,
                resolutionDetail);
        if (acknowledgedAtEpochMillis != null
                && acknowledgedAtEpochMillis < firstSeenAtEpochMillis) {
            throw new IllegalArgumentException("Invalid acknowledgement timestamp");
        }
        if (resolvedAtEpochMillis != null
                && resolvedAtEpochMillis < lastSeenAtEpochMillis) {
            throw new IllegalArgumentException("Resolution cannot precede last seen");
        }
    }

    private static void validateStatusMetadata(
            Status status,
            Long acknowledgedAtEpochMillis,
            String acknowledgedBy,
            Long resolvedAtEpochMillis,
            String resolutionDetail) {
        switch (status) {
            case OPEN -> validateOpenMetadata(
                    acknowledgedAtEpochMillis,
                    acknowledgedBy,
                    resolvedAtEpochMillis,
                    resolutionDetail);
            case ACKNOWLEDGED -> validateAcknowledgedMetadata(
                    acknowledgedAtEpochMillis,
                    acknowledgedBy,
                    resolvedAtEpochMillis,
                    resolutionDetail);
            case RESOLVED -> validateResolvedMetadata(resolvedAtEpochMillis, resolutionDetail);
            default -> throw new IllegalStateException("Unsupported anomaly status: " + status);
        }
    }

    private static void validateOpenMetadata(
            Long acknowledgedAtEpochMillis,
            String acknowledgedBy,
            Long resolvedAtEpochMillis,
            String resolutionDetail) {
        if (acknowledgedAtEpochMillis != null
                || acknowledgedBy != null
                || resolvedAtEpochMillis != null
                || resolutionDetail != null) {
            throw new IllegalArgumentException(
                    "Open anomaly must not contain acknowledgement or resolution");
        }
    }

    private static void validateAcknowledgedMetadata(
            Long acknowledgedAtEpochMillis,
            String acknowledgedBy,
            Long resolvedAtEpochMillis,
            String resolutionDetail) {
        if (acknowledgedAtEpochMillis == null || acknowledgedBy == null) {
            throw new IllegalArgumentException(
                    "Acknowledged anomaly requires acknowledgement metadata");
        }
        if (resolvedAtEpochMillis != null || resolutionDetail != null) {
            throw new IllegalArgumentException(
                    "Acknowledged anomaly must not contain resolution metadata");
        }
    }

    private static void validateResolvedMetadata(
            Long resolvedAtEpochMillis, String resolutionDetail) {
        if (resolvedAtEpochMillis == null || resolutionDetail == null) {
            throw new IllegalArgumentException(
                    "Resolved anomaly requires resolution metadata");
        }
    }

    private static String requireText(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return normalized;
    }

    public enum Type {
        DUPLICATE_INSTANCE,
        MALFORMED_STACK,
        CONFLICTING_OBSERVATION,
        IDENTITY_MISMATCH
    }

    public enum Status {
        OPEN,
        ACKNOWLEDGED,
        RESOLVED;

        public void requireTransitionTo(Status target) {
            Objects.requireNonNull(target, "target");
            boolean allowed = switch (this) {
                case OPEN -> target == ACKNOWLEDGED || target == RESOLVED;
                case ACKNOWLEDGED -> target == RESOLVED;
                case RESOLVED -> false;
            };
            if (!allowed) {
                throw new IllegalStateException(
                        "Illegal anomaly transition from " + this + " to " + target);
            }
        }
    }
}
