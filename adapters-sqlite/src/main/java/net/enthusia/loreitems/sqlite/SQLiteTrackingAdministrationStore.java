package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.LoreItemsAdministrationUseCase;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.TrackingAdministrationStore;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstance;

final class SQLiteTrackingAdministrationStore implements TrackingAdministrationStore {
    private static final String CONFLICTING = "CONFLICTING";
    private static final String DUPLICATE = "DUPLICATE_INSTANCE";
    private static final int SINGLE_ROW = 1;

    private final SQLiteStorageRuntime storage;
    private final SQLiteDefinitionRepository definitions;
    private final SQLiteInstanceRepository instances;

    SQLiteTrackingAdministrationStore(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
        definitions = new SQLiteDefinitionRepository(storage);
        instances = new SQLiteInstanceRepository(storage);
    }

    @Override
    public CompletionStage<Page<LoreDefinition>> listDefinitions(PageRequest request) {
        return definitions.listActive(Objects.requireNonNull(request, "request"));
    }

    @Override
    public CompletionStage<Page<LoreInstance>> listInstances(
            LoreDefinitionId definitionId, PageRequest request) {
        return instances.listByDefinition(
                Objects.requireNonNull(definitionId, "definitionId"),
                Objects.requireNonNull(request, "request"));
    }

    @Override
    public CompletionStage<LoreItemsAdministrationUseCase.DuplicateResolutionResult>
            resolveDuplicate(
                    LoreItemsAdministrationUseCase.DuplicateResolutionRequest request,
                    Instant resolvedAt) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(resolvedAt, "resolvedAt");
        return storage.execute(connection -> {
            try {
                return SQLiteTransactions.inTransaction(connection, transaction ->
                        resolveInTransaction(transaction, request, resolvedAt.toEpochMilli()));
            } catch (StaleResolutionException exception) {
                return result(
                        LoreItemsAdministrationUseCase.DuplicateResolutionStatus.STALE,
                        "The anomaly or current state changed before resolution completed.");
            }
        });
    }

    private static LoreItemsAdministrationUseCase.DuplicateResolutionResult resolveInTransaction(
            Connection connection,
            LoreItemsAdministrationUseCase.DuplicateResolutionRequest request,
            long resolvedAt) throws SQLException, StaleResolutionException {
        AnomalyRow anomaly = findAnomaly(connection, request.anomalyId().toString());
        LoreItemsAdministrationUseCase.DuplicateResolutionResult validation =
                validateAnomaly(anomaly, request);
        if (validation != null) {
            return validation;
        }
        ObservationRow selected = findObservation(
                connection, request.selectedObservationId(), anomaly.instanceId());
        validation = validateSelection(selected, anomaly);
        if (validation != null) {
            return validation;
        }
        CurrentRow current = findCurrent(connection, anomaly.instanceId());
        validation = validateCurrent(current);
        if (validation != null) {
            return validation;
        }
        long observationId = insertResolutionObservation(
                connection, anomaly, selected, resolvedAt);
        if (!updateCurrent(connection, anomaly, selected, current, observationId, resolvedAt)
                || !resolveAnomaly(connection, request, resolvedAt, selected)) {
            throw new StaleResolutionException();
        }
        appendAudit(connection, request, anomaly, selected, resolvedAt);
        return result(
                LoreItemsAdministrationUseCase.DuplicateResolutionStatus.RESOLVED,
                "The selected location is confirmed; physical copies were not deleted.");
    }

    private static LoreItemsAdministrationUseCase.DuplicateResolutionResult validateAnomaly(
            AnomalyRow anomaly,
            LoreItemsAdministrationUseCase.DuplicateResolutionRequest request) {
        if (anomaly == null) {
            return result(
                    LoreItemsAdministrationUseCase.DuplicateResolutionStatus.NOT_FOUND,
                    "The selected anomaly no longer exists.");
        }
        if (!DUPLICATE.equals(anomaly.type())) {
            return result(
                    LoreItemsAdministrationUseCase.DuplicateResolutionStatus.NOT_DUPLICATE,
                    "Only duplicate-instance anomalies support location selection.");
        }
        if (!anomaly.active()
                || anomaly.stateRevision() != request.expectedAnomalyRevision()) {
            return result(
                    LoreItemsAdministrationUseCase.DuplicateResolutionStatus.STALE,
                    "The selected duplicate anomaly changed before confirmation.");
        }
        return null;
    }

    private static LoreItemsAdministrationUseCase.DuplicateResolutionResult validateSelection(
            ObservationRow selected, AnomalyRow anomaly) {
        if (selected == null
                || !selected.selectable()
                || selected.observedAt() < anomaly.firstSeenAt()) {
            return result(
                    LoreItemsAdministrationUseCase.DuplicateResolutionStatus.INVALID_SELECTION,
                    "The selected observation is not evidence for this active duplicate conflict.");
        }
        return null;
    }

    private static LoreItemsAdministrationUseCase.DuplicateResolutionResult validateCurrent(
            CurrentRow current) {
        if (current == null || !CONFLICTING.equals(current.state())) {
            return result(
                    LoreItemsAdministrationUseCase.DuplicateResolutionStatus.STALE,
                    "The instance is no longer in conflicting current state.");
        }
        return null;
    }

    private static AnomalyRow findAnomaly(Connection connection, String anomalyId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT instance_id, definition_id, anomaly_type, status, state_revision, "
                        + "first_seen_at FROM instance_anomalies WHERE anomaly_id = ?")) {
            statement.setString(1, anomalyId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new AnomalyRow(
                        resultSet.getString("instance_id"),
                        resultSet.getString("definition_id"),
                        resultSet.getString("anomaly_type"),
                        resultSet.getString("status"),
                        resultSet.getLong("state_revision"),
                        resultSet.getLong("first_seen_at"));
            }
        }
    }

    private static ObservationRow findObservation(
            Connection connection, long observationId, String instanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT location_type, location_key, container_path, confidence, observed_at "
                        + "FROM instance_observations WHERE observation_id = ? "
                        + "AND instance_id = ?")) {
            statement.setLong(1, observationId);
            statement.setString(2, instanceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new ObservationRow(
                        LocationDescriptor.Type.valueOf(resultSet.getString("location_type")),
                        resultSet.getString("location_key"),
                        resultSet.getString("container_path"),
                        InstanceObservation.Confidence.valueOf(
                                resultSet.getString("confidence")),
                        resultSet.getLong("observed_at"));
            }
        }
    }

    private static CurrentRow findCurrent(Connection connection, String instanceId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state, state_revision FROM instance_current_state WHERE instance_id = ?")) {
            statement.setString(1, instanceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? new CurrentRow(
                                resultSet.getString("state"),
                                resultSet.getLong("state_revision"))
                        : null;
            }
        }
    }

    private static long insertResolutionObservation(
            Connection connection,
            AnomalyRow anomaly,
            ObservationRow selected,
            long resolvedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO instance_observations(instance_id, definition_id, location_type, "
                        + "location_key, container_path, confidence, source, observed_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED_NOW', "
                        + "'staff-duplicate-resolution', ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, anomaly.instanceId());
            statement.setString(2, anomaly.definitionId());
            statement.setString(3, selected.type().name());
            statement.setString(4, selected.key());
            setNullableString(statement, 5, selected.path());
            statement.setLong(6, resolvedAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Resolution observation did not return an identifier");
                }
                return keys.getLong(1);
            }
        }
    }

    private static boolean updateCurrent(
            Connection connection,
            AnomalyRow anomaly,
            ObservationRow selected,
            CurrentRow current,
            long observationId,
            long resolvedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE instance_current_state SET state = 'CONFIRMED_NOW', "
                        + "location_type = ?, location_key = ?, container_path = ?, "
                        + "last_observation_id = ?, state_revision = state_revision + 1, "
                        + "updated_at = ? WHERE instance_id = ? AND state = 'CONFLICTING' "
                        + "AND state_revision = ? AND updated_at <= ?")) {
            statement.setString(1, selected.type().name());
            statement.setString(2, selected.key());
            setNullableString(statement, 3, selected.path());
            statement.setLong(4, observationId);
            statement.setLong(5, resolvedAt);
            statement.setString(6, anomaly.instanceId());
            statement.setLong(7, current.stateRevision());
            statement.setLong(8, resolvedAt);
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    private static boolean resolveAnomaly(
            Connection connection,
            LoreItemsAdministrationUseCase.DuplicateResolutionRequest request,
            long resolvedAt,
            ObservationRow selected) throws SQLException {
        String detail = "Selected " + describe(selected)
                + "; physical copies were preserved and future scans may reopen conflict.";
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE instance_anomalies SET status = 'RESOLVED', resolved_at = ?, "
                        + "resolution_detail = ?, state_revision = state_revision + 1 "
                        + "WHERE anomaly_id = ? AND state_revision = ? "
                        + "AND anomaly_type = 'DUPLICATE_INSTANCE' "
                        + "AND status IN ('OPEN', 'ACKNOWLEDGED') AND last_seen_at <= ?")) {
            statement.setLong(1, resolvedAt);
            statement.setString(2, detail);
            statement.setString(3, request.anomalyId().toString());
            statement.setLong(4, request.expectedAnomalyRevision());
            statement.setLong(5, resolvedAt);
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    private static void appendAudit(
            Connection connection,
            LoreItemsAdministrationUseCase.DuplicateResolutionRequest request,
            AnomalyRow anomaly,
            ObservationRow selected,
            long resolvedAt) throws SQLException {
        String detail = "{\"anomalyId\":\"" + request.anomalyId()
                + "\",\"selectedObservationId\":" + request.selectedObservationId()
                + ",\"location\":\"" + SQLiteJson.escape(describe(selected)) + "\"}";
        SQLiteAuditRepository.appendInTransaction(connection, AuditEventRecord.pending(
                "lore_instance",
                anomaly.instanceId(),
                "duplicate_location_selected",
                "staff",
                request.actorId(),
                detail,
                resolvedAt));
    }

    private static void setNullableString(
            PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static String describe(ObservationRow selected) {
        return selected.type().name() + ':' + selected.key()
                + (selected.path() == null ? "" : ':' + selected.path());
    }

    private static LoreItemsAdministrationUseCase.DuplicateResolutionResult result(
            LoreItemsAdministrationUseCase.DuplicateResolutionStatus status,
            String detail) {
        return LoreItemsAdministrationUseCase.DuplicateResolutionResult.of(status, detail);
    }

    private record AnomalyRow(
            String instanceId,
            String definitionId,
            String type,
            String status,
            long stateRevision,
            long firstSeenAt) {
        private boolean active() {
            return "OPEN".equals(status) || "ACKNOWLEDGED".equals(status);
        }
    }

    private record CurrentRow(String state, long stateRevision) {}

    private record ObservationRow(
            LocationDescriptor.Type type,
            String key,
            String path,
            InstanceObservation.Confidence confidence,
            long observedAt) {
        private boolean selectable() {
            if (confidence != InstanceObservation.Confidence.CONFLICTING) {
                return false;
            }
            return switch (type) {
                case PLAYER_INVENTORY,
                        PLAYER_ENDER_CHEST,
                        BLOCK_CONTAINER,
                        DROPPED_ITEM,
                        ITEM_FRAME,
                        ITEM_DISPLAY,
                        ARMOR_STAND,
                        NESTED_CONTAINER -> true;
                default -> false;
            };
        }
    }

    private static final class StaleResolutionException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
