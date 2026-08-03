package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.DisplayItemObservationStore;
import net.enthusia.loreitems.application.DisplayItemObservationUseCase;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LocationDescriptor;

public final class SQLiteDisplayItemObservationStore implements DisplayItemObservationStore {
    private static final String AGGREGATE_TYPE = "lore_instance";
    private static final String ACTOR_TYPE = "system";
    private static final String PRESENT_EVENT = "display_item_confirmed";
    private static final String ABSENT_EVENT = "display_item_last_confirmed";
    private static final int SINGLE_ROW = 1;

    private final SQLiteStorageRuntime storage;

    public SQLiteDisplayItemObservationStore(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<DisplayItemObservationUseCase.Result> record(
            DisplayItemObservationUseCase.Request request,
            Instant observedAt) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(observedAt, "observedAt");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> recordInTransaction(
                        transaction, request, observedAt.toEpochMilli())));
    }

    private static DisplayItemObservationUseCase.Result recordInTransaction(
            Connection connection,
            DisplayItemObservationUseCase.Request request,
            long observedAt) throws SQLException {
        InstanceRow instance = findInstance(
                connection, request.identity().instanceId().value());
        if (instance == null) {
            return result(
                    DisplayItemObservationUseCase.Status.UNKNOWN_INSTANCE,
                    "The tracked display identity has no durable instance record.");
        }
        if (!instance.definitionId().equals(request.identity().definitionId().value())
                || instance.appliedRevision()
                        != request.identity().appliedRevision().value()) {
            return result(
                    DisplayItemObservationUseCase.Status.IDENTITY_MISMATCH,
                    "The displayed identity does not match the durable instance record.");
        }
        if (!"ACTIVE".equals(instance.lifecycleState())) {
            return result(
                    DisplayItemObservationUseCase.Status.INACTIVE_INSTANCE,
                    "The durable instance is not active.");
        }
        if (hasBlockingAnomaly(connection, request.identity().instanceId().value())) {
            return result(
                    DisplayItemObservationUseCase.Status.BLOCKED_ANOMALY,
                    "An unresolved identity anomaly blocks display-state replacement.");
        }

        CurrentRow current = findCurrentState(
                connection, request.identity().instanceId().value());
        if (current == null) {
            return result(
                    DisplayItemObservationUseCase.Status.STALE,
                    "The instance has no current-state projection to advance.");
        }
        if ("CONFLICTING".equals(current.state())
                || "TERMINAL_VOID".equals(current.state())) {
            return result(
                    DisplayItemObservationUseCase.Status.BLOCKED_ANOMALY,
                    "Conflicting or terminal current state was preserved.");
        }

        if (request.presence() == DisplayItemObservationUseCase.Presence.PRESENT) {
            return recordPresent(connection, request, observedAt, current);
        }
        return recordAbsent(connection, request, observedAt, current);
    }

    private static DisplayItemObservationUseCase.Result recordPresent(
            Connection connection,
            DisplayItemObservationUseCase.Request request,
            long observedAt,
            CurrentRow current) throws SQLException {
        if ("CONFIRMED_NOW".equals(current.state())
                && request.location().equals(current.location())) {
            return result(
                    DisplayItemObservationUseCase.Status.UNCHANGED,
                    "The display slot is already the confirmed current location.");
        }
        long observationId = insertObservation(
                connection,
                request,
                InstanceObservation.Confidence.CONFIRMED_NOW,
                observedAt);
        requireCurrentStateUpdate(
                connection,
                request,
                current.stateRevision(),
                InstanceCurrentState.State.CONFIRMED_NOW,
                observationId,
                observedAt,
                false);
        appendAudit(connection, request, PRESENT_EVENT, observedAt);
        return result(
                DisplayItemObservationUseCase.Status.RECORDED,
                "The display slot is now confirmed.");
    }

    private static DisplayItemObservationUseCase.Result recordAbsent(
            Connection connection,
            DisplayItemObservationUseCase.Request request,
            long observedAt,
            CurrentRow current) throws SQLException {
        if (!request.location().equals(current.location())) {
            return result(
                    DisplayItemObservationUseCase.Status.STALE,
                    "The display-removal evidence no longer matches the current location.");
        }
        if ("LAST_CONFIRMED".equals(current.state())) {
            return result(
                    DisplayItemObservationUseCase.Status.UNCHANGED,
                    "The display slot is already retained only as last-confirmed evidence.");
        }
        if (!"CONFIRMED_NOW".equals(current.state())) {
            return result(
                    DisplayItemObservationUseCase.Status.STALE,
                    "The display-removal evidence cannot replace the current state.");
        }
        long observationId = insertObservation(
                connection,
                request,
                InstanceObservation.Confidence.LAST_CONFIRMED,
                observedAt);
        requireCurrentStateUpdate(
                connection,
                request,
                current.stateRevision(),
                InstanceCurrentState.State.LAST_CONFIRMED,
                observationId,
                observedAt,
                true);
        appendAudit(connection, request, ABSENT_EVENT, observedAt);
        return result(
                DisplayItemObservationUseCase.Status.RECORDED,
                "The removed display slot was retained as last-confirmed evidence.");
    }

    private static InstanceRow findInstance(Connection connection, UUID instanceId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT definition_id, applied_revision, lifecycle_state "
                        + "FROM lore_instances WHERE instance_id = ?")) {
            statement.setString(1, instanceId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? new InstanceRow(
                                UUID.fromString(resultSet.getString("definition_id")),
                                resultSet.getLong("applied_revision"),
                                resultSet.getString("lifecycle_state"))
                        : null;
            }
        }
    }

    private static boolean hasBlockingAnomaly(Connection connection, UUID instanceId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM instance_anomalies WHERE instance_id = ? "
                        + "AND status IN ('OPEN', 'ACKNOWLEDGED') "
                        + "AND anomaly_type IN ('DUPLICATE_INSTANCE', 'MALFORMED_STACK', "
                        + "'CONFLICTING_OBSERVATION', 'IDENTITY_MISMATCH') LIMIT 1")) {
            statement.setString(1, instanceId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static CurrentRow findCurrentState(Connection connection, UUID instanceId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state, location_type, location_key, container_path, state_revision "
                        + "FROM instance_current_state WHERE instance_id = ?")) {
            statement.setString(1, instanceId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                String locationType = resultSet.getString("location_type");
                LocationDescriptor location = locationType == null
                        ? null
                        : new LocationDescriptor(
                                LocationDescriptor.Type.valueOf(locationType),
                                resultSet.getString("location_key"),
                                resultSet.getString("container_path"));
                return new CurrentRow(
                        resultSet.getString("state"),
                        location,
                        resultSet.getLong("state_revision"));
            }
        }
    }

    private static long insertObservation(
            Connection connection,
            DisplayItemObservationUseCase.Request request,
            InstanceObservation.Confidence confidence,
            long observedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO instance_observations(instance_id, definition_id, location_type, "
                        + "location_key, container_path, confidence, source, observed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, request.identity().instanceId().value().toString());
            statement.setString(2, request.identity().definitionId().value().toString());
            statement.setString(3, request.location().type().name());
            statement.setString(4, request.location().locationKey());
            statement.setString(5, request.location().containerPath());
            statement.setString(6, confidence.name());
            statement.setString(7, request.source());
            statement.setLong(8, observedAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException(
                            "Display observation did not return an identifier");
                }
                return keys.getLong(1);
            }
        }
    }

    private static void requireCurrentStateUpdate(
            Connection connection,
            DisplayItemObservationUseCase.Request request,
            long expectedRevision,
            InstanceCurrentState.State targetState,
            long observationId,
            long observedAt,
            boolean requireMatchingLocation) throws SQLException {
        String matchingLocation = requireMatchingLocation
                ? "AND location_type = ? AND location_key = ? AND container_path = ? "
                : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE instance_current_state SET state = ?, location_type = ?, "
                        + "location_key = ?, container_path = ?, last_observation_id = ?, "
                        + "state_revision = state_revision + 1, updated_at = ? "
                        + "WHERE instance_id = ? AND state_revision = ? "
                        + "AND state NOT IN ('CONFLICTING', 'TERMINAL_VOID') "
                        + matchingLocation)) {
            statement.setString(1, targetState.name());
            statement.setString(2, request.location().type().name());
            statement.setString(3, request.location().locationKey());
            statement.setString(4, request.location().containerPath());
            statement.setLong(5, observationId);
            statement.setLong(6, observedAt);
            statement.setString(7, request.identity().instanceId().value().toString());
            statement.setLong(8, expectedRevision);
            if (requireMatchingLocation) {
                statement.setString(9, request.location().type().name());
                statement.setString(10, request.location().locationKey());
                statement.setString(11, request.location().containerPath());
            }
            if (statement.executeUpdate() != SINGLE_ROW) {
                throw new SQLException(
                        "Display observation lost the expected current-state revision");
            }
        }
    }

    private static void appendAudit(
            Connection connection,
            DisplayItemObservationUseCase.Request request,
            String eventType,
            long occurredAt) throws SQLException {
        SQLiteAuditRepository.appendInTransaction(connection, AuditEventRecord.pending(
                AGGREGATE_TYPE,
                request.identity().instanceId().value().toString(),
                eventType,
                ACTOR_TYPE,
                null,
                detail(request),
                occurredAt));
    }

    private static String detail(DisplayItemObservationUseCase.Request request) {
        return "{\"locationType\":\"" + request.location().type().name()
                + "\",\"locationKey\":\"" + jsonEscape(request.location().locationKey())
                + "\",\"containerPath\":\""
                + jsonEscape(request.location().containerPath())
                + "\",\"presence\":\"" + request.presence().name()
                + "\",\"source\":\"" + jsonEscape(request.source()) + "\"}";
    }

    private static String jsonEscape(String value) {
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

    private static DisplayItemObservationUseCase.Result result(
            DisplayItemObservationUseCase.Status status,
            String detail) {
        return DisplayItemObservationUseCase.Result.of(status, detail);
    }

    private record InstanceRow(
            UUID definitionId,
            long appliedRevision,
            String lifecycleState) {}

    private record CurrentRow(
            String state,
            LocationDescriptor location,
            long stateRevision) {}
}
