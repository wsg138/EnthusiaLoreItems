package net.enthusia.loreitems.application;

import java.util.Objects;

public record AuditEventRecord(
        long auditId,
        String aggregateType,
        String aggregateId,
        String eventType,
        String actorType,
        String actorId,
        String detailJson,
        long occurredAtEpochMillis) {
    public static final int MAX_TYPE_LENGTH = 120;
    public static final int MAX_ID_LENGTH = 200;
    public static final int MAX_DETAIL_JSON_LENGTH = 65_536;

    public AuditEventRecord {
        if (auditId < 0L) {
            throw new IllegalArgumentException("auditId must not be negative");
        }
        aggregateType = normalizeRequired(aggregateType, "aggregateType", MAX_TYPE_LENGTH);
        aggregateId = normalizeRequired(aggregateId, "aggregateId", MAX_ID_LENGTH);
        eventType = normalizeRequired(eventType, "eventType", MAX_TYPE_LENGTH);
        actorType = normalizeRequired(actorType, "actorType", MAX_TYPE_LENGTH);
        if (actorId != null) {
            actorId = actorId.strip();
            if (actorId.isEmpty() || actorId.length() > MAX_ID_LENGTH) {
                throw new IllegalArgumentException("Invalid actorId");
            }
        }
        Objects.requireNonNull(detailJson, "detailJson");
        if (detailJson.isBlank() || detailJson.length() > MAX_DETAIL_JSON_LENGTH) {
            throw new IllegalArgumentException("Invalid detailJson");
        }
    }

    public static AuditEventRecord pending(
            String aggregateType,
            String aggregateId,
            String eventType,
            String actorType,
            String actorId,
            String detailJson,
            long occurredAtEpochMillis) {
        return new AuditEventRecord(
                0L,
                aggregateType,
                aggregateId,
                eventType,
                actorType,
                actorId,
                detailJson,
                occurredAtEpochMillis);
    }

    private static String normalizeRequired(String value, String name, int maxLength) {
        Objects.requireNonNull(value, name);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return normalized;
    }
}
