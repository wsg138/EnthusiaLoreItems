package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.TrackingObservationStore;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LocationDescriptor;

/** Atomically advances durable observation history and the current-state projection. */
public final class SQLiteTrackingObservationStore implements TrackingObservationStore {
    private static final String ACTIVE = "ACTIVE";
    private static final String CONFIRMED_NOW = "CONFIRMED_NOW";
    private static final String LAST_CONFIRMED = "LAST_CONFIRMED";
    private static final String CONFLICTING = "CONFLICTING";
    private static final String TERMINAL_VOID = "TERMINAL_VOID";
    private static final String MISSING_UNRESOLVED = "MISSING_UNRESOLVED";
    private static final String AGGREGATE_TYPE = "lore_instance";
    private static final String ACTOR_TYPE = "system";
    private static final String ACTOR_ID = "paper-tracking";
    private static final int SINGLE_ROW = 1;

    private final SQLiteStorageRuntime storage;

    public SQLiteTrackingObservationStore(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<TrackingObservationUseCase.Result> record(
            TrackingObservationUseCase.Request request,
            Instant observedAt) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(observedAt, "observedAt");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> recordInTransaction(
                        transaction, request, observedAt.toEpochMilli())));
    }

    private static TrackingObservationUseCase.Result recordInTransaction(
            Connection connection,
            TrackingObservationUseCase.Request request,
            long observedAt) throws SQLException {
        InstanceRow instance = findInstance(connection, request);
        TrackingObservationUseCase.Result validation = validateInstance(instance, request);
        if (validation != null) {
            return validation;
        }
        CurrentRow current = findCurrent(connection, request);
        if (current == null) {
            return result(
                    TrackingObservationUseCase.Status.STALE,
                    "The instance has no durable current-state projection.");
        }
        if (TERMINAL_VOID.equals(current.state())) {
            return result(
                    TrackingObservationUseCase.Status.INACTIVE_INSTANCE,
                    "Terminal void state cannot be replaced by tracking evidence.");
        }
        if (hasNonDuplicateBlockingAnomaly(connection, request)) {
            return result(
                    TrackingObservationUseCase.Status.BLOCKED_ANOMALY,
                    "An unresolved identity anomaly blocks location replacement.");
        }
        return request.presence() == TrackingObservationUseCase.Presence.PRESENT
                ? recordPresent(connection, request, current, observedAt)
                : recordLastConfirmed(connection, request, current, observedAt);
    }

    private static TrackingObservationUseCase.Result recordPresent(
            Connection connection,
            TrackingObservationUseCase.Request request,
            CurrentRow current,
            long observedAt) throws SQLException {
        if (CONFLICTING.equals(current.state())) {
            return appendConflictEvidence(connection, request, observedAt);
        }
        if (request.location().equals(current.location())) {
            if (CONFIRMED_NOW.equals(current.state())) {
                return result(
                        TrackingObservationUseCase.Status.UNCHANGED,
                        "The location is already confirmed now.");
            }
            return advance(
                    connection,
                    request,
                    current,
                    InstanceObservation.Confidence.CONFIRMED_NOW,
                    InstanceCurrentState.State.CONFIRMED_NOW,
                    observedAt,
                    "tracking_location_confirmed");
        }
        if (mayReplaceCurrent(request, current)) {
            return advance(
                    connection,
                    request,
                    current,
                    InstanceObservation.Confidence.CONFIRMED_NOW,
                    InstanceCurrentState.State.CONFIRMED_NOW,
                    observedAt,
                    "tracking_location_moved");
        }
        return recordConflict(connection, request, current, observedAt);
    }

    private static TrackingObservationUseCase.Result recordLastConfirmed(
            Connection connection,
            TrackingObservationUseCase.Request request,
            CurrentRow current,
            long observedAt) throws SQLException {
        if (CONFLICTING.equals(current.state())) {
            return result(
                    TrackingObservationUseCase.Status.BLOCKED_ANOMALY,
                    "Conflicting state was preserved while the location became inaccessible.");
        }
        if (!request.location().equals(current.location())) {
            return result(
                    TrackingObservationUseCase.Status.STALE,
                    "Last-confirmed evidence no longer matches the durable current location.");
        }
        if (LAST_CONFIRMED.equals(current.state())) {
            return result(
                    TrackingObservationUseCase.Status.UNCHANGED,
                    "The location is already retained as last confirmed.");
        }
        if (!CONFIRMED_NOW.equals(current.state())) {
            return result(
                    TrackingObservationUseCase.Status.STALE,
                    "Only a confirmed-now location can become last confirmed.");
        }
        return advance(
                connection,
                request,
                current,
                InstanceObservation.Confidence.LAST_CONFIRMED,
                InstanceCurrentState.State.LAST_CONFIRMED,
                observedAt,
                "tracking_location_unloaded");
    }

    private static boolean mayReplaceCurrent(
            TrackingObservationUseCase.Request request,
            CurrentRow current) {
        if (request.mode() == TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION
                || LAST_CONFIRMED.equals(current.state())
                || MISSING_UNRESOLVED.equals(current.state())
                || current.location() == null) {
            return true;
        }
        LocationDescriptor previous = current.location();
        return previous.type() == LocationDescriptor.Type.QUEUED_DELIVERY
                || previous.type() == LocationDescriptor.Type.PENDING_MUTATION;
    }

    private static TrackingObservationUseCase.Result advance(
            Connection connection,
            TrackingObservationUseCase.Request request,
            CurrentRow current,
            InstanceObservation.Confidence confidence,
            InstanceCurrentState.State state,
            long observedAt,
            String eventType) throws SQLException {
        long observationId = insertObservation(
                connection, request, request.location(), confidence, observedAt);
        requireCurrentUpdate(
                connection,
                request,
                current,
                request.location(),
                state,
                observationId,
                observedAt);
        appendAudit(connection, request, eventType, observedAt, request.location());
        return result(
                TrackingObservationUseCase.Status.RECORDED,
                state == InstanceCurrentState.State.CONFIRMED_NOW
                        ? "The physical location is now confirmed."
                        : "The physical location is retained as last confirmed.");
    }

    private static TrackingObservationUseCase.Result recordConflict(
            Connection connection,
            TrackingObservationUseCase.Request request,
            CurrentRow current,
            long observedAt) throws SQLException {
        LocationDescriptor previous = Objects.requireNonNull(
                current.location(), "A confirmed state must have a location");
        insertObservation(
                connection,
                request,
                previous,
                InstanceObservation.Confidence.CONFLICTING,
                observedAt);
        insertObservation(
                connection,
                request,
                request.location(),
                InstanceObservation.Confidence.CONFLICTING,
                observedAt);
        LocationDescriptor fence = conflictLocation(request, previous);
        long fenceObservationId = insertObservation(
                connection,
                request,
                fence,
                InstanceObservation.Confidence.CONFLICTING,
                observedAt);
        requireCurrentUpdate(
                connection,
                request,
                current,
                fence,
                InstanceCurrentState.State.CONFLICTING,
                fenceObservationId,
                observedAt);
        upsertDuplicateAnomaly(connection, request, previous, observedAt);
        appendAudit(connection, request, "tracking_duplicate_fenced", observedAt, fence);
        return result(
                TrackingObservationUseCase.Status.CONFLICT_RECORDED,
                "Conflicting live locations were preserved and fenced for staff review.");
    }

    private static TrackingObservationUseCase.Result appendConflictEvidence(
            Connection connection,
            TrackingObservationUseCase.Request request,
            long observedAt) throws SQLException {
        insertObservation(
                connection,
                request,
                request.location(),
                InstanceObservation.Confidence.CONFLICTING,
                observedAt);
        refreshDuplicateAnomaly(connection, request, observedAt);
        appendAudit(
                connection,
                request,
                "tracking_duplicate_evidence_added",
                observedAt,
                request.location());
        return result(
                TrackingObservationUseCase.Status.CONFLICT_RECORDED,
                "Additional duplicate-location evidence was preserved.");
    }

    private static InstanceRow findInstance(
            Connection connection,
            TrackingObservationUseCase.Request request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT definition_id, applied_revision, lifecycle_state "
                        + "FROM lore_instances WHERE instance_id = ?")) {
            statement.setString(1, request.identity().instanceId().value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? new InstanceRow(
                                resultSet.getString("definition_id"),
                                resultSet.getLong("applied_revision"),
                                resultSet.getString("lifecycle_state"))
                        : null;
            }
        }
    }

    private static TrackingObservationUseCase.Result validateInstance(
            InstanceRow instance,
            TrackingObservationUseCase.Request request) {
        if (instance == null) {
            return result(
                    TrackingObservationUseCase.Status.UNKNOWN_INSTANCE,
                    "The observed identity has no durable instance record.");
        }
        if (!instance.definitionId().equals(
                        request.identity().definitionId().value().toString())
                || instance.appliedRevision()
                        != request.identity().appliedRevision().value()) {
            return result(
                    TrackingObservationUseCase.Status.IDENTITY_MISMATCH,
                    "The observed identity does not match the durable instance record.");
        }
        if (!ACTIVE.equals(instance.lifecycleState())) {
            return result(
                    TrackingObservationUseCase.Status.INACTIVE_INSTANCE,
                    "The durable instance is not active.");
        }
        return null;
    }

    private static CurrentRow findCurrent(
            Connection connection,
            TrackingObservationUseCase.Request request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state, location_type, location_key, container_path, state_revision "
                        + "FROM instance_current_state WHERE instance_id = ?")) {
            statement.setString(1, request.identity().instanceId().value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                String type = resultSet.getString("location_type");
                LocationDescriptor location = type == null
                        ? null
                        : new LocationDescriptor(
                                LocationDescriptor.Type.valueOf(type),
                                resultSet.getString("location_key"),
                                resultSet.getString("container_path"));
                return new CurrentRow(
                        resultSet.getString("state"),
                        location,
                        resultSet.getLong("state_revision"));
            }
        }
    }

    private static boolean hasNonDuplicateBlockingAnomaly(
            Connection connection,
            TrackingObservationUseCase.Request request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM instance_anomalies WHERE instance_id = ? "
                        + "AND status IN ('OPEN', 'ACKNOWLEDGED') "
                        + "AND anomaly_type <> 'DUPLICATE_INSTANCE' LIMIT 1")) {
            statement.setString(1, request.identity().instanceId().value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static long insertObservation(
            Connection connection,
            TrackingObservationUseCase.Request request,
            LocationDescriptor location,
            InstanceObservation.Confidence confidence,
            long observedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO instance_observations(instance_id, definition_id, location_type, "
                        + "location_key, container_path, confidence, source, observed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, request.identity().instanceId().value().toString());
            statement.setString(2, request.identity().definitionId().value().toString());
            statement.setString(3, location.type().name());
            statement.setString(4, location.locationKey());
            setNullableString(statement, 5, location.containerPath());
            statement.setString(6, confidence.name());
            statement.setString(7, request.source());
            statement.setLong(8, observedAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Tracking observation did not return an identifier");
                }
                return keys.getLong(1);
            }
        }
    }

    private static void requireCurrentUpdate(
            Connection connection,
            TrackingObservationUseCase.Request request,
            CurrentRow current,
            LocationDescriptor location,
            InstanceCurrentState.State state,
            long observationId,
            long observedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE instance_current_state SET state = ?, location_type = ?, "
                        + "location_key = ?, container_path = ?, last_observation_id = ?, "
                        + "state_revision = state_revision + 1, updated_at = ? "
                        + "WHERE instance_id = ? AND state_revision = ? "
                        + "AND state <> 'TERMINAL_VOID' AND updated_at <= ?")) {
            statement.setString(1, state.name());
            statement.setString(2, location.type().name());
            statement.setString(3, location.locationKey());
            setNullableString(statement, 4, location.containerPath());
            statement.setLong(5, observationId);
            statement.setLong(6, observedAt);
            statement.setString(7, request.identity().instanceId().value().toString());
            statement.setLong(8, current.stateRevision());
            statement.setLong(9, observedAt);
            if (statement.executeUpdate() != SINGLE_ROW) {
                throw new SQLException("Tracking observation lost the current-state revision");
            }
        }
    }

    private static LocationDescriptor conflictLocation(
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

    private static void upsertDuplicateAnomaly(
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

    private static void refreshDuplicateAnomaly(
            Connection connection,
            TrackingObservationUseCase.Request request,
            long observedAt) throws SQLException {
        refreshDuplicateAnomaly(
                connection,
                request,
                observedAt,
                "Additional duplicate location observed at " + describe(request.location()));
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

    private static void appendAudit(
            Connection connection,
            TrackingObservationUseCase.Request request,
            String eventType,
            long occurredAt,
            LocationDescriptor location) throws SQLException {
        String detail = "{\"locationType\":\"" + location.type().name()
                + "\",\"locationKey\":\"" + escapeJson(location.locationKey())
                + "\",\"containerPath\":" + nullableJson(location.containerPath())
                + ",\"presence\":\"" + request.presence().name()
                + "\",\"mode\":\"" + request.mode().name()
                + "\",\"source\":\"" + escapeJson(request.source()) + "\"}";
        SQLiteAuditRepository.appendInTransaction(connection, AuditEventRecord.pending(
                AGGREGATE_TYPE,
                request.identity().instanceId().value().toString(),
                eventType,
                ACTOR_TYPE,
                ACTOR_ID,
                detail,
                occurredAt));
    }

    private static void setNullableString(
            PreparedStatement statement,
            int index,
            String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static String nullableJson(String value) {
        return value == null ? "null" : "\"" + escapeJson(value) + "\"";
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static String describe(LocationDescriptor location) {
        return location.type().name() + ':' + location.locationKey()
                + (location.containerPath() == null ? "" : ':' + location.containerPath());
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static TrackingObservationUseCase.Result result(
            TrackingObservationUseCase.Status status,
            String detail) {
        return TrackingObservationUseCase.Result.of(status, detail);
    }

    private record InstanceRow(
            String definitionId,
            long appliedRevision,
            String lifecycleState) {}

    private record CurrentRow(
            String state,
            LocationDescriptor location,
            long stateRevision) {}
}
