package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase.Observation;
import net.enthusia.loreitems.application.LoreItemIdentity;

/** Restores marker-backed full-delete work when later physical evidence follows an abort. */
final class SQLiteAbortedDeleteRecovery {
    private static final int SINGLE_ROW = 1;
    private final SQLiteStorageRuntime storage;

    SQLiteAbortedDeleteRecovery(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    CompletionStage<Void> reactivate(Observation observation, Instant now) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(now, "now");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> {
                    recover(transaction, observation, now.toEpochMilli());
                    return null;
                }));
    }

    private static void recover(
            Connection connection,
            Observation observation,
            long now) throws SQLException {
        UUID operationId = findAbortedDeleteOperation(connection, observation);
        if (operationId == null) {
            return;
        }
        String targetState = findTargetState(connection, operationId, observation);
        if (targetState != null) {
            reopenExistingTarget(connection, operationId, observation, targetState, now);
            return;
        }
        createLateTarget(connection, operationId, observation, now);
    }

    private static UUID findAbortedDeleteOperation(
            Connection connection,
            Observation observation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT operation.operation_id FROM destructive_operations operation "
                        + "JOIN deleted_definition_markers marker "
                        + "ON marker.definition_id = operation.definition_id "
                        + "WHERE operation.definition_id = ? "
                        + "AND operation.operation_type = 'DELETE_DEFINITION' "
                        + "AND operation.state = 'ABORTED' "
                        + "ORDER BY operation.accepted_at DESC, operation.operation_id DESC LIMIT 1")) {
            statement.setString(1, observation.identity().definitionId().value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? UUID.fromString(resultSet.getString("operation_id"))
                        : null;
            }
        }
    }

    private static String findTargetState(
            Connection connection,
            UUID operationId,
            Observation observation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state FROM destructive_targets WHERE operation_id = ? "
                        + "AND instance_id = ? AND definition_id = ?")) {
            statement.setString(1, operationId.toString());
            statement.setString(2, observation.identity().instanceId().value().toString());
            statement.setString(3, observation.identity().definitionId().value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("state") : null;
            }
        }
    }

    private static void reopenExistingTarget(
            Connection connection,
            UUID operationId,
            Observation observation,
            String targetState,
            long now) throws SQLException {
        if (!"ABORTED".equals(targetState) && !"COMPLETED".equals(targetState)) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE destructive_targets SET state = 'PENDING', effect_state = 'UNKNOWN', "
                        + "claim_token = NULL, claim_expires_at = NULL, expected_fingerprint = NULL, "
                        + "before_fingerprint = NULL, after_fingerprint = NULL, last_error = ?, "
                        + "updated_at = ? WHERE operation_id = ? AND instance_id = ? "
                        + "AND state = ? AND NOT EXISTS (SELECT 1 FROM destructive_targets other "
                        + "WHERE other.instance_id = ? "
                        + "AND other.state NOT IN ('COMPLETED', 'ABORTED') "
                        + "AND NOT (other.operation_id = ? AND other.instance_id = ?))")) {
            statement.setString(1,
                    "A late physical copy reactivated evidence-aborted full-delete work.");
            statement.setLong(2, now);
            statement.setString(3, operationId.toString());
            statement.setString(4, observation.identity().instanceId().value().toString());
            statement.setString(5, targetState);
            statement.setString(6, observation.identity().instanceId().value().toString());
            statement.setString(7, operationId.toString());
            statement.setString(8, observation.identity().instanceId().value().toString());
            if (statement.executeUpdate() != SINGLE_ROW) {
                return;
            }
        }
        reactivateParent(connection, operationId, now);
        appendAudit(
                connection,
                operationId,
                observation,
                "destructive_late_copy_reopened",
                now);
    }

    private static void createLateTarget(
            Connection connection,
            UUID operationId,
            Observation observation,
            long now) throws SQLException {
        ensureLateInstance(connection, observation.identity(), now);
        if (!insertLateTarget(connection, operationId, observation, now)) {
            return;
        }
        incrementTargetCountAndReactivate(connection, operationId, now);
        appendAudit(
                connection,
                operationId,
                observation,
                "destructive_late_delete_target_created",
                now);
    }

    private static void ensureLateInstance(
            Connection connection,
            LoreItemIdentity identity,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO lore_instances(instance_id, definition_id, applied_revision, "
                        + "desired_revision, lifecycle_state, created_at, terminal_at) "
                        + "VALUES (?, ?, ?, ?, 'ACTIVE', ?, NULL)")) {
            statement.setString(1, identity.instanceId().value().toString());
            statement.setString(2, identity.definitionId().value().toString());
            statement.setLong(3, identity.appliedRevision().value());
            statement.setLong(4, identity.appliedRevision().value());
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    private static boolean insertLateTarget(
            Connection connection,
            UUID operationId,
            Observation observation,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO destructive_targets(operation_id, instance_id, definition_id, "
                        + "expected_applied_revision, expected_location_type, expected_location_key, "
                        + "expected_container_path, expected_fingerprint, state, effect_state, "
                        + "claim_token, claim_expires_at, attempt_count, before_fingerprint, "
                        + "after_fingerprint, last_error, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, 'PENDING', 'UNKNOWN', NULL, NULL, 0, "
                        + "NULL, NULL, ?, ?, ?)")) {
            statement.setString(1, operationId.toString());
            statement.setString(2, observation.identity().instanceId().value().toString());
            statement.setString(3, observation.identity().definitionId().value().toString());
            statement.setLong(4, observation.identity().appliedRevision().value());
            statement.setString(5, observation.locationType());
            statement.setString(6, observation.locationKey());
            SQLiteDestructiveRows.setNullableString(statement, 7, observation.containerPath());
            statement.setString(8,
                    "A late physical copy appeared after an evidence-aborted full delete.");
            statement.setLong(9, now);
            statement.setLong(10, now);
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    private static void reactivateParent(
            Connection connection,
            UUID operationId,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE destructive_operations SET state = 'ACTIVE', updated_at = ?, "
                        + "terminal_at = NULL WHERE operation_id = ? AND state = 'ABORTED'")) {
            statement.setLong(1, now);
            statement.setString(2, operationId.toString());
            if (statement.executeUpdate() != SINGLE_ROW) {
                throw new SQLException("Aborted full-delete parent changed during late-copy recovery");
            }
        }
    }

    private static void incrementTargetCountAndReactivate(
            Connection connection,
            UUID operationId,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE destructive_operations SET target_count = target_count + 1, "
                        + "state = 'ACTIVE', updated_at = ?, terminal_at = NULL "
                        + "WHERE operation_id = ? AND state = 'ABORTED'")) {
            statement.setLong(1, now);
            statement.setString(2, operationId.toString());
            if (statement.executeUpdate() != SINGLE_ROW) {
                throw new SQLException("Aborted full-delete parent changed during late-target recovery");
            }
        }
    }

    private static void appendAudit(
            Connection connection,
            UUID operationId,
            Observation observation,
            String eventType,
            long now) throws SQLException {
        SQLiteDestructiveControlStore.appendAudit(
                connection,
                operationId,
                eventType,
                "SYSTEM",
                "{\"instanceId\":\"" + observation.identity().instanceId().value() + "\"}",
                now);
    }
}
