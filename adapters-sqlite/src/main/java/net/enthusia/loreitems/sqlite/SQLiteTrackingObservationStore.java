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

    private static boolean hasActiveConflictEvidence(
            Connection connection,
            TrackingObservationUseCase.Request request) throws SQLException {
        LocationDescriptor location = request.location();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM instance_observations observation "
                        + "WHERE observation.instance_id = ? AND observation.confidence = 'CONFLICTING' "
                        + "AND observation.location_type = ? AND observation.location_key = ? "
                        + "AND ((observation.container_path IS NULL AND ? IS NULL) "
                        + "OR observation.container_path = ?) LIMIT 1")) {
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

    private static InstanceObservation.Confidence confidenceFor(
            TrackingObservationUseCase.Presence presence) {
        return presence == TrackingObservationUseCase.Presence.PRESENT
                ? InstanceObservation.Confidence.CONFIRMED_NOW
                : InstanceObservation.Confidence.LAST_CONFIRMED;
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
                    throw new SQLException("Observation insert did not return a generated id");
                }
                return keys.getLong(1);
            }
        }
    }

    private static void requireCurrentUpdate(
            Connection connection,
            TrackingObservationUseCase.Request request,
            CurrentRow expected,
            LocationDescriptor location,
            InstanceCurrentState.State state,
            long observationId,
            long observedAt) throws SQLException, StaleTrackingObservationException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE instance_current_state SET state = ?, location_type = ?, location_key = ?, "
                        + "container_path = ?, observation_id = ?, state_revision = state_revision + 1, "
                        + "updated_at = ? WHERE instance_id = ? AND state_revision = ? AND updated_at <= ?")) {
            statement.setString(1, state.name());
            statement.setString(2, location.type().name());
            statement.setString(3, location.locationKey());
            setNullableString(statement, 4, location.containerPath());
            statement.setLong(5, observationId);
            statement.setLong(6, observedAt);
            statement.setString(7, request.identity().instanceId().value().toString());
            statement.setLong(8, expected.stateRevision());
            statement.setLong(9, observedAt);
            if (statement.executeUpdate() != SINGLE_ROW) {
                throw new StaleTrackingObservationException();
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
}
