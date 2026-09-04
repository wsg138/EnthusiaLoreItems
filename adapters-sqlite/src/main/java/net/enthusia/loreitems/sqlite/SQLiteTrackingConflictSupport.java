package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.LocationDescriptor;

/** Duplicate fencing, anomaly refresh, and audit serialization for tracking transactions. */
final class SQLiteTrackingConflictSupport {
    private static final String AGGREGATE_TYPE = "lore_instance";
    private static final String ACTOR_TYPE = "system";
    private static final String ACTOR_ID = "paper-tracking";
    private static final String ENTITY_MARKER = ":entity:";
    private static final int UUID_TEXT_LENGTH = 36;

    private SQLiteTrackingConflictSupport() {}

    static LocationDescriptor conflictLocation(
            TrackingObservationUseCase.Request request,
            LocationDescriptor previous) {
        String path = truncate(
                "copy1=" + describe(previous) + ";copy2=" + describe(request.location()),
                LocationDescriptor.MAX_CONTAINER_PATH_LENGTH);
        return new LocationDescriptor(
                LocationDescriptor.Type.DUPLICATE_CONFLICT,
                "instance:" + request.identity().instanceId().value(),
                path);
    }

    static void upsertDuplicateAnomaly(
            Connection connection,
            TrackingObservationUseCase.Request request,
            LocationDescriptor previous,
            long observedAt) throws SQLException {
        String detail = duplicateDetail(previous, request.location());
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO instance_anomalies(anomaly_id, instance_id, definition_id, "
                        + "anomaly_type, status, detail, first_seen_at, last_seen_at, "
                        + "acknowledged_at, acknowledged_by, resolved_at, resolution_detail, "
                        + "state_revision) VALUES (?, ?, ?, 'DUPLICATE_INSTANCE', 'OPEN', ?, ?, ?, "
                        + "NULL, NULL, NULL, NULL, 0) ON CONFLICT DO NOTHING")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, request.identity().instanceId().value().toString());
            statement.setString(3, request.identity().definitionId().value().toString());
            statement.setString(4, detail);
            statement.setLong(5, observedAt);
            statement.setLong(6, observedAt);
            if (statement.executeUpdate() == 0) {
                refreshDuplicateAnomaly(connection, request, observedAt, detail);
            }
        }
    }

    static void refreshDuplicateAnomaly(
            Connection connection,
            TrackingObservationUseCase.Request request,
            long observedAt) throws SQLException {
        refreshDuplicateAnomaly(
                connection,
                request,
                observedAt,
                "Additional duplicate location observed at " + describe(request.location()));
    }

    static boolean sameDroppedEntity(
            LocationDescriptor first,
            LocationDescriptor second) {
        return first != null
                && second != null
                && first.type() == LocationDescriptor.Type.DROPPED_ITEM
                && second.type() == LocationDescriptor.Type.DROPPED_ITEM
                && sameEntityUuid(first, second);
    }

    static boolean samePhysicalEntity(
            LocationDescriptor first,
            LocationDescriptor second) {
        if (first == null || second == null || first.type() != second.type()) {
            return false;
        }
        return switch (first.type()) {
            case DROPPED_ITEM, ITEM_FRAME, ITEM_DISPLAY, ARMOR_STAND ->
                    sameEntityUuid(first, second)
                            && Objects.equals(first.containerPath(), second.containerPath());
            default -> false;
        };
    }

    static void appendAudit(
            Connection connection,
            TrackingObservationUseCase.Request request,
            String eventType,
            long occurredAt,
            LocationDescriptor location) throws SQLException {
        String detail = "{\"locationType\":\"" + location.type().name()
                + "\",\"locationKey\":\"" + SQLiteJson.escape(location.locationKey())
                + "\",\"containerPath\":" + nullableJson(location.containerPath())
                + ",\"presence\":\"" + request.presence().name()
                + "\",\"mode\":\"" + request.mode().name()
                + "\",\"source\":\"" + SQLiteJson.escape(request.source()) + "\"}";
        SQLiteAuditRepository.appendInTransaction(connection, AuditEventRecord.pending(
                AGGREGATE_TYPE,
                request.identity().instanceId().value().toString(),
                eventType,
                ACTOR_TYPE,
                ACTOR_ID,
                detail,
                occurredAt));
    }

    static void setNullableString(
            PreparedStatement statement,
            int index,
            String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void refreshDuplicateAnomaly(
            Connection connection,
            TrackingObservationUseCase.Request request,
            long observedAt,
            String detail) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE instance_anomalies SET detail = ?, last_seen_at = ?, "
                        + "state_revision = state_revision + 1 WHERE instance_id = ? "
                        + "AND definition_id = ? AND anomaly_type = 'DUPLICATE_INSTANCE' "
                        + "AND status IN ('OPEN', 'ACKNOWLEDGED') AND last_seen_at <= ?")) {
            statement.setString(1, truncate(detail, InstanceAnomaly.MAX_DETAIL_LENGTH));
            statement.setLong(2, observedAt);
            statement.setString(3, request.identity().instanceId().value().toString());
            statement.setString(4, request.identity().definitionId().value().toString());
            statement.setLong(5, observedAt);
            statement.executeUpdate();
        }
    }

    private static String duplicateDetail(
            LocationDescriptor first,
            LocationDescriptor second) {
        return truncate(
                "Same lore instance observed at " + describe(first) + " and " + describe(second)
                        + ". Copies were preserved and current state was fenced.",
                InstanceAnomaly.MAX_DETAIL_LENGTH);
    }

    private static boolean sameEntityUuid(
            LocationDescriptor first,
            LocationDescriptor second) {
        UUID firstEntity = entityUuid(first);
        UUID secondEntity = entityUuid(second);
        return firstEntity != null && firstEntity.equals(secondEntity);
    }

    private static UUID entityUuid(LocationDescriptor location) {
        String key = location.locationKey();
        String candidate;
        if (location.type() == LocationDescriptor.Type.DROPPED_ITEM) {
            int marker = key.indexOf(ENTITY_MARKER);
            if (marker < 0) {
                return null;
            }
            int start = marker + ENTITY_MARKER.length();
            int end = start + UUID_TEXT_LENGTH;
            if (key.length() < end) {
                return null;
            }
            candidate = key.substring(start, end);
        } else {
            if (key.length() < UUID_TEXT_LENGTH) {
                return null;
            }
            candidate = key.substring(key.length() - UUID_TEXT_LENGTH);
        }
        try {
            return UUID.fromString(candidate);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String nullableJson(String value) {
        return value == null ? "null" : "\"" + SQLiteJson.escape(value) + "\"";
    }

    private static String describe(LocationDescriptor location) {
        return location.type().name() + ':' + location.locationKey()
                + (location.containerPath() == null ? "" : ':' + location.containerPath());
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
