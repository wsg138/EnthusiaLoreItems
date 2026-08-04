package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.ItemAnomalyObservationStore;
import net.enthusia.loreitems.application.ItemAnomalyObservationUseCase;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.LocationDescriptor;

public final class SQLiteItemAnomalyObservationStore
        implements ItemAnomalyObservationStore {
    private static final String ACTIVE_LIFECYCLE = "ACTIVE";
    private static final String TERMINAL_STATE = "TERMINAL_VOID";
    private static final String AGGREGATE_TYPE = "lore_instance";
    private static final String EVENT_TYPE = "identity_anomaly_observed";
    private static final String ACTOR_TYPE = "system";
    private static final String ACTOR_ID = "paper-event-protection";
    private static final int SINGLE_ROW = 1;
    private static final long NO_OBSERVATION_ID = 0L;
    private static final int JSON_ESCAPE_CAPACITY = 16;
    private static final int CONTROL_CHARACTER_LIMIT = 0x20;

    private final SQLiteStorageRuntime storage;

    public SQLiteItemAnomalyObservationStore(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<ItemAnomalyObservationUseCase.Result> record(
            Observation observation) {
        Objects.requireNonNull(observation, "observation");
        return storage.execute(connection -> {
            try {
                return SQLiteTransactions.inTransaction(
                        connection,
                        transaction -> recordInTransaction(transaction, observation));
            } catch (StaleCurrentStateException exception) {
                return ItemAnomalyObservationUseCase.Result.of(
                        ItemAnomalyObservationUseCase.Status.STALE,
                        "The durable current state changed before anomaly evidence was fenced.");
            }
        });
    }

    private static ItemAnomalyObservationUseCase.Result recordInTransaction(
            Connection connection,
            Observation observation) throws SQLException, StaleCurrentStateException {
        ItemAnomalyObservationUseCase.Request request = observation.request();
        InstanceRow instance = findInstance(connection, request.identity());
        ItemAnomalyObservationUseCase.Result validation = validateInstance(instance, request);
        if (validation != null) {
            return validation;
        }

        CurrentStateRow current = findCurrentState(connection, request.identity());
        validation = validateCurrentState(current);
        if (validation != null) {
            return validation;
        }

        long observationId = insertEvidenceObservations(connection, observation);
        if (!fenceCurrentState(connection, observation, current, observationId)) {
            throw new StaleCurrentStateException();
        }
        return persistAnomaly(connection, observation, request);
    }

    private static ItemAnomalyObservationUseCase.Result validateInstance(
            InstanceRow instance,
            ItemAnomalyObservationUseCase.Request request) {
        if (instance == null) {
            return result(
                    ItemAnomalyObservationUseCase.Status.UNKNOWN_INSTANCE,
                    "No durable lore instance matches the observed identity.");
        }
        boolean identityMatches = instance.definitionId().equals(
                        request.identity().definitionId().value().toString())
                && instance.appliedRevision()
                        == request.identity().appliedRevision().value();
        if (!identityMatches) {
            return result(
                    ItemAnomalyObservationUseCase.Status.IDENTITY_MISMATCH,
                    "The observed definition or revision does not match durable identity.");
        }
        if (!ACTIVE_LIFECYCLE.equals(instance.lifecycleState())) {
            return result(
                    ItemAnomalyObservationUseCase.Status.INACTIVE_INSTANCE,
                    "The observed lore instance is no longer active.");
        }
        return null;
    }

    private static ItemAnomalyObservationUseCase.Result validateCurrentState(
            CurrentStateRow current) {
        if (current == null) {
            return result(
                    ItemAnomalyObservationUseCase.Status.STALE,
                    "The durable current-state row is unavailable.");
        }
        if (TERMINAL_STATE.equals(current.state())) {
            return result(
                    ItemAnomalyObservationUseCase.Status.TERMINAL_INSTANCE,
                    "Terminal void state cannot be replaced by anomaly evidence.");
        }
        return null;
    }

    private static ItemAnomalyObservationUseCase.Result persistAnomaly(
            Connection connection,
            Observation observation,
            ItemAnomalyObservationUseCase.Request request)
            throws SQLException, StaleCurrentStateException {
        String detail = anomalyDetail(request);
        ExistingAnomaly existing = findActiveAnomaly(connection, request);
        ItemAnomalyObservationUseCase.Status status;
        if (existing == null) {
            insertAnomaly(connection, observation, detail);
            status = ItemAnomalyObservationUseCase.Status.RECORDED;
        } else {
            refreshAnomaly(connection, existing, detail, observation.observedAtEpochMillis());
            status = ItemAnomalyObservationUseCase.Status.REFRESHED;
        }
        appendAudit(connection, observation, detail, status);
        return result(status, detail);
    }

    private static InstanceRow findInstance(
            Connection connection, LoreItemIdentity identity) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT definition_id, applied_revision, lifecycle_state "
                        + "FROM lore_instances WHERE instance_id = ?")) {
            statement.setString(1, identity.instanceId().value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new InstanceRow(
                        resultSet.getString("definition_id"),
                        resultSet.getLong("applied_revision"),
                        resultSet.getString("lifecycle_state"));
            }
        }
    }

    private static CurrentStateRow findCurrentState(
            Connection connection, LoreItemIdentity identity) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state, state_revision FROM instance_current_state "
                        + "WHERE instance_id = ?")) {
            statement.setString(1, identity.instanceId().value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new CurrentStateRow(
                        resultSet.getString("state"),
                        resultSet.getLong("state_revision"));
            }
        }
    }

    private static long insertEvidenceObservations(
            Connection connection,
            Observation observation) throws SQLException {
        ItemAnomalyObservationUseCase.Request request = observation.request();
        Set<LocationDescriptor> locations = new LinkedHashSet<>(request.evidenceLocations());
        locations.add(request.location());
        long currentObservationId = NO_OBSERVATION_ID;
        for (LocationDescriptor location : locations) {
            long observationId = insertObservation(connection, observation, location);
            if (location.equals(request.location())) {
                currentObservationId = observationId;
            }
        }
        if (currentObservationId == NO_OBSERVATION_ID) {
            throw new SQLException("Anomaly current-state observation was not inserted");
        }
        return currentObservationId;
    }

    private static long insertObservation(
            Connection connection,
            Observation observation,
            LocationDescriptor location) throws SQLException {
        ItemAnomalyObservationUseCase.Request request = observation.request();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO instance_observations(instance_id, definition_id, "
                        + "location_type, location_key, container_path, confidence, source, "
                        + "observed_at) VALUES (?, ?, ?, ?, ?, 'CONFLICTING', ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, request.identity().instanceId().value().toString());
            statement.setString(2, request.identity().definitionId().value().toString());
            statement.setString(3, location.type().name());
            statement.setString(4, location.locationKey());
            if (location.containerPath() == null) {
                statement.setNull(5, Types.VARCHAR);
            } else {
                statement.setString(5, location.containerPath());
            }
            statement.setString(6, request.source());
            statement.setLong(7, observation.observedAtEpochMillis());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Anomaly observation did not return an identifier");
                }
                return keys.getLong(1);
            }
        }
    }

    private static boolean fenceCurrentState(
            Connection connection,
            Observation observation,
            CurrentStateRow current,
            long observationId) throws SQLException {
        ItemAnomalyObservationUseCase.Request request = observation.request();
        LocationDescriptor location = request.location();
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE instance_current_state SET state = 'CONFLICTING', "
                        + "location_type = ?, location_key = ?, container_path = ?, "
                        + "last_observation_id = ?, state_revision = state_revision + 1, "
                        + "updated_at = ? WHERE instance_id = ? AND state_revision = ? "
                        + "AND state <> 'TERMINAL_VOID' AND updated_at <= ?")) {
            statement.setString(1, location.type().name());
            statement.setString(2, location.locationKey());
            if (location.containerPath() == null) {
                statement.setNull(3, Types.VARCHAR);
            } else {
                statement.setString(3, location.containerPath());
            }
            statement.setLong(4, observationId);
            statement.setLong(5, observation.observedAtEpochMillis());
            statement.setString(6, request.identity().instanceId().value().toString());
            statement.setLong(7, current.stateRevision());
            statement.setLong(8, observation.observedAtEpochMillis());
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    private static ExistingAnomaly findActiveAnomaly(
            Connection connection,
            ItemAnomalyObservationUseCase.Request request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT anomaly_id, state_revision FROM instance_anomalies "
                        + "WHERE anomaly_type = ? AND instance_id = ? AND definition_id = ? "
                        + "AND status IN ('OPEN', 'ACKNOWLEDGED')")) {
            statement.setString(1, request.kind().anomalyType().name());
            statement.setString(2, request.identity().instanceId().value().toString());
            statement.setString(3, request.identity().definitionId().value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new ExistingAnomaly(
                        resultSet.getString("anomaly_id"),
                        resultSet.getLong("state_revision"));
            }
        }
    }

    private static void insertAnomaly(
            Connection connection,
            Observation observation,
            String detail) throws SQLException {
        ItemAnomalyObservationUseCase.Request request = observation.request();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO instance_anomalies(anomaly_id, instance_id, definition_id, "
                        + "anomaly_type, status, detail, first_seen_at, last_seen_at, "
                        + "acknowledged_at, acknowledged_by, resolved_at, resolution_detail, "
                        + "state_revision) VALUES (?, ?, ?, ?, 'OPEN', ?, ?, ?, NULL, NULL, "
                        + "NULL, NULL, 0)")) {
            statement.setString(1, observation.anomalyId().toString());
            statement.setString(2, request.identity().instanceId().value().toString());
            statement.setString(3, request.identity().definitionId().value().toString());
            statement.setString(4, request.kind().anomalyType().name());
            statement.setString(5, detail);
            statement.setLong(6, observation.observedAtEpochMillis());
            statement.setLong(7, observation.observedAtEpochMillis());
            statement.executeUpdate();
        }
    }

    private static void refreshAnomaly(
            Connection connection,
            ExistingAnomaly existing,
            String detail,
            long observedAt) throws SQLException, StaleCurrentStateException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE instance_anomalies SET detail = ?, last_seen_at = ?, "
                        + "state_revision = state_revision + 1 WHERE anomaly_id = ? "
                        + "AND state_revision = ? AND status IN ('OPEN', 'ACKNOWLEDGED') "
                        + "AND last_seen_at <= ?")) {
            statement.setString(1, detail);
            statement.setLong(2, observedAt);
            statement.setString(3, existing.anomalyId());
            statement.setLong(4, existing.stateRevision());
            statement.setLong(5, observedAt);
            if (statement.executeUpdate() != SINGLE_ROW) {
                throw new StaleCurrentStateException();
            }
        }
    }

    private static void appendAudit(
            Connection connection,
            Observation observation,
            String detail,
            ItemAnomalyObservationUseCase.Status status) throws SQLException {
        ItemAnomalyObservationUseCase.Request request = observation.request();
        String json = "{\"anomalyType\":\"" + request.kind().anomalyType().name()
                + "\",\"status\":\"" + status.name()
                + "\",\"evidenceLocations\":" + request.evidenceLocations().size()
                + ",\"source\":\"" + escapeJson(request.source())
                + "\",\"detail\":\"" + escapeJson(detail) + "\"}";
        SQLiteAuditRepository.appendInTransaction(connection, AuditEventRecord.pending(
                AGGREGATE_TYPE,
                request.identity().instanceId().value().toString(),
                EVENT_TYPE,
                ACTOR_TYPE,
                ACTOR_ID,
                json,
                observation.observedAtEpochMillis()));
    }

    private static String anomalyDetail(ItemAnomalyObservationUseCase.Request request) {
        LocationDescriptor location = request.location();
        String path = location.containerPath() == null
                ? ""
                : " path=" + location.containerPath();
        String detail = request.kind().name() + " at " + location.type().name()
                + " key=" + location.locationKey() + path
                + " source=" + request.source() + ": " + request.detail();
        return detail.length() <= InstanceAnomaly.MAX_DETAIL_LENGTH
                ? detail
                : detail.substring(0, InstanceAnomaly.MAX_DETAIL_LENGTH);
    }

    private static ItemAnomalyObservationUseCase.Result result(
            ItemAnomalyObservationUseCase.Status status,
            String detail) {
        return ItemAnomalyObservationUseCase.Result.of(status, detail);
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + JSON_ESCAPE_CAPACITY);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < CONTROL_CHARACTER_LIMIT) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private record InstanceRow(
            String definitionId,
            long appliedRevision,
            String lifecycleState) {}

    private record CurrentStateRow(String state, long stateRevision) {}

    private record ExistingAnomaly(String anomalyId, long stateRevision) {}

    private static final class StaleCurrentStateException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
