package net.enthusia.loreitems.sqlite;

import static net.enthusia.loreitems.sqlite.SQLiteTrackingConflictSupport.appendAudit;
import static net.enthusia.loreitems.sqlite.SQLiteTrackingConflictSupport.conflictLocation;
import static net.enthusia.loreitems.sqlite.SQLiteTrackingConflictSupport.refreshDuplicateAnomaly;
import static net.enthusia.loreitems.sqlite.SQLiteTrackingConflictSupport.samePhysicalEntity;
import static net.enthusia.loreitems.sqlite.SQLiteTrackingConflictSupport.setNullableString;
import static net.enthusia.loreitems.sqlite.SQLiteTrackingConflictSupport.upsertDuplicateAnomaly;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.TrackingObservationStore;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
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
        return storage.execute(connection -> {
            try {
                return SQLiteTransactions.inTransaction(
                        connection,
                        transaction -> recordInTransaction(
                                transaction, request, observedAt.toEpochMilli()));
            } catch (StaleTrackingObservationException exception) {
                return result(
                        TrackingObservationUseCase.Status.STALE,
                        "The durable current state changed before tracking evidence was applied.");
            }
        });
    }

    private static TrackingObservationUseCase.Result recordInTransaction(
            Connection connection,
            TrackingObservationUseCase.Request request,
            long observedAt) throws SQLException, StaleTrackingObservationException {
        InstanceRow instance = findInstance(connection, request);
        if (instance == null) {
            return result(
                    TrackingObservationUseCase.Status.UNKNOWN_INSTANCE,
                    "The observed identity has no durable instance record.");
        }
        if (!ACTIVE.equals(instance.lifecycleState())) {
            return result(
                    TrackingObservationUseCase.Status.INACTIVE_INSTANCE,
                    "The durable instance is not active.");
        }
        if (!identityMatches(instance, request)) {
            return recordIdentityMismatch(connection, request, instance, observedAt);
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

    private static boolean identityMatches(
            InstanceRow instance,
            TrackingObservationUseCase.Request request) {
        return instance.definitionId().equals(
                        request.identity().definitionId().value().toString())
                && instance.appliedRevision()
                        == request.identity().appliedRevision().value();
    }

    private static TrackingObservationUseCase.Result recordIdentityMismatch(
            Connection connection,
            TrackingObservationUseCase.Request request,
            InstanceRow instance,
            long observedAt) throws SQLException, StaleTrackingObservationException {
        CurrentRow current = findCurrent(connection, request);
        if (current == null) {
            return result(
                    TrackingObservationUseCase.Status.IDENTITY_MISMATCH,
                    "The observed identity does not match the durable instance record, and the "
                            + "current-state projection is unavailable for fencing.");
        }
        if (TERMINAL_VOID.equals(current.state())) {
            return result(
                    TrackingObservationUseCase.Status.IDENTITY_MISMATCH,
                    "The observed identity does not match the durable instance record; terminal "
                            + "void state was preserved.");
        }

        long observationId = insertObservation(
                connection,
                request,
                instance.definitionId(),
                request.location(),
                InstanceObservation.Confidence.CONFLICTING,
                observedAt);
        if (!CONFLICTING.equals(current.state())) {
            requireCurrentUpdate(
                    connection,
                    request,
                    current,
                    request.location(),
                    InstanceCurrentState.State.CONFLICTING,
                    observationId,
                    observedAt);
        }
        upsertIdentityMismatchAnomaly(connection, request, instance, current, observedAt);
        appendAudit(
                connection,
                request,
                "tracking_identity_mismatch_fenced",
                observedAt,
                request.location());
        return result(
                TrackingObservationUseCase.Status.IDENTITY_MISMATCH,
                "The mismatched physical identity was preserved as conflicting evidence and "
                        + "fenced for staff review.");
    }

    private static void upsertIdentityMismatchAnomaly(
            Connection connection,
            TrackingObservationUseCase.Request request,
            InstanceRow instance,
            CurrentRow current,
            long observedAt) throws SQLException {
        String detail = identityMismatchDetail(request, instance, current);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO instance_anomalies(anomaly_id, instance_id, definition_id, "
                        + "anomaly_type, status, detail, first_seen_at, last_seen_at, "
                        + "acknowledged_at, acknowledged_by, resolved_at, resolution_detail, "
                        + "state_revision) VALUES (?, ?, ?, 'IDENTITY_MISMATCH', 'OPEN', ?, ?, ?, "
                        + "NULL, NULL, NULL, NULL, 0) ON CONFLICT DO NOTHING")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, request.identity().instanceId().value().toString());
            statement.setString(3, instance.definitionId());
            statement.setString(4, detail);
            statement.setLong(5, observedAt);
            statement.setLong(6, observedAt);
            if (statement.executeUpdate() == 0) {
                refreshIdentityMismatchAnomaly(
                        connection, request, instance.definitionId(), detail, observedAt);
            }
        }
    }

    private static void refreshIdentityMismatchAnomaly(
            Connection connection,
            TrackingObservationUseCase.Request request,
            String durableDefinitionId,
            String detail,
            long observedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE instance_anomalies SET detail = ?, last_seen_at = ?, "
                        + "state_revision = state_revision + 1 WHERE instance_id = ? "
                        + "AND definition_id = ? AND anomaly_type = 'IDENTITY_MISMATCH' "
                        + "AND status IN ('OPEN', 'ACKNOWLEDGED') AND last_seen_at <= ?")) {
            statement.setString(1, detail);
            statement.setLong(2, observedAt);
            statement.setString(3, request.identity().instanceId().value().toString());
            statement.setString(4, durableDefinitionId);
            statement.setLong(5, observedAt);
            statement.executeUpdate();
        }
    }

    private static String identityMismatchDetail(
            TrackingObservationUseCase.Request request,
            InstanceRow instance,
            CurrentRow current) {
        String previous = current.location() == null
                ? "none"
                : current.location().type().name() + ':' + current.location().locationKey()
                        + (current.location().containerPath() == null
                                ? ""
                                : ':' + current.location().containerPath());
        LocationDescriptor observed = request.location();
        String observedLocation = observed.type().name() + ':' + observed.locationKey()
                + (observed.containerPath() == null ? "" : ':' + observed.containerPath());
        return "Identity mismatch observed at " + observedLocation
                + "; previous durable location=" + previous
                + "; durable definition=" + instance.definitionId()
                + " revision=" + instance.appliedRevision()
                + "; observed definition="
                + request.identity().definitionId().value()
                + " revision=" + request.identity().appliedRevision().value()
                + ". Physical evidence was preserved and current state was fenced.";
    }

    private static TrackingObservationUseCase.Result recordPresent(
            Connection connection,
            TrackingObservationUseCase.Request request,
            CurrentRow current,
            long observedAt) throws SQLException, StaleTrackingObservationException {
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
            long observedAt) throws SQLException, StaleTrackingObservationException {
        if (CONFLICTING.equals(current.state())) {
            return result(
                    TrackingObservationUseCase.Status.BLOCKED_ANOMALY,
                    "Conflicting state was preserved while the location became inaccessible.");
        }
        if (!request.location().equals(current.location())
                && !samePhysicalEntity(request.location(), current.location())) {
            return result(
                    TrackingObservationUseCase.Status.STALE,
                    "Last-confirmed evidence no longer matches the durable current location.");
        }
        if (LAST_CONFIRMED.equals(current.state())
                && request.location().equals(current.location())) {
            return result(
                    TrackingObservationUseCase.Status.UNCHANGED,
                    "The location is already retained as last confirmed.");
        }
        if (!CONFIRMED_NOW.equals(current.state())
                && !LAST_CONFIRMED.equals(current.state())) {
            return result(
                    TrackingObservationUseCase.Status.STALE,
                    "Only a confirmed location can become last confirmed.");
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
                || current.location() == null
                || samePhysicalEntity(request.location(), current.location())) {
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
            String eventType) throws SQLException, StaleTrackingObservationException {
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
                InstanceCurrentState.State.CONFIRMED_NOW.equals(state)
                        ? "The physical location is now confirmed."
                        : "The physical location is retained as last confirmed.");
    }

    private static TrackingObservationUseCase.Result recordConflict(
            Connection connection,
            TrackingObservationUseCase.Request request,
            CurrentRow current,
            long observedAt) throws SQLException, StaleTrackingObservationException {
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
        if (hasActiveConflictEvidence(connection, request)) {
            return result(
                    TrackingObservationUseCase.Status.UNCHANGED,
                    "This conflicting physical location is already preserved.");
        }
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

    private static boolean hasActiveConflictEvidence(
            Connection connection,
            TrackingObservationUseCase.Request request) throws SQLException {
        LocationDescriptor location = request.location();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM instance_observations observation "
                        + "JOIN instance_anomalies anomaly "
                        + "ON anomaly.instance_id = observation.instance_id "
                        + "WHERE observation.instance_id = ? "
                        + "AND observation.location_type = ? "
                        + "AND observation.location_key = ? "
                        + "AND ((observation.container_path IS NULL AND ? IS NULL) "
                        + "OR observation.container_path = ?) "
                        + "AND observation.confidence = 'CONFLICTING' "
                        + "AND anomaly.anomaly_type = 'DUPLICATE_INSTANCE' "
                        + "AND anomaly.status IN ('OPEN', 'ACKNOWLEDGED') "
                        + "AND observation.observed_at >= anomaly.first_seen_at LIMIT 1")) {
            statement.setString(1, request.identity().instanceId().value().toString());
            statement.setString(2, location.type().name());
            statement.setString(3, location.locationKey());
            setNullableString(statement, 4, location.containerPath());
            setNullableString(statement, 5, location.containerPath());
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
        return insertObservation(
                connection,
                request,
                request.identity().definitionId().value().toString(),
                location,
                confidence,
                observedAt);
    }

    private static long insertObservation(
            Connection connection,
            TrackingObservationUseCase.Request request,
            String definitionId,
            LocationDescriptor location,
            InstanceObservation.Confidence confidence,
            long observedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO instance_observations(instance_id, definition_id, location_type, "
                        + "location_key, container_path, confidence, source, observed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, request.identity().instanceId().value().toString());
            statement.setString(2, definitionId);
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
            long observedAt) throws SQLException, StaleTrackingObservationException {
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
            int updated = statement.executeUpdate();
            if (updated == 0) {
                throw new StaleTrackingObservationException();
            }
            if (updated != SINGLE_ROW) {
                throw new SQLException(
                        "Tracking observation updated multiple current-state rows");
            }
        }
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

    private static final class StaleTrackingObservationException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
