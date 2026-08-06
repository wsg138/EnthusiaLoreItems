package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.OperationView;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.Preview;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.PreviewRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.StartRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.StartResult;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.StartStatus;
import net.enthusia.loreitems.domain.LoreDefinitionId;

final class SQLiteDestructiveAcceptanceStore {
    private static final int SINGLE_ROW = 1;
    private final SQLiteStorageRuntime storage;
    private final SQLiteDestructiveQueryStore queries;

    SQLiteDestructiveAcceptanceStore(
            SQLiteStorageRuntime storage,
            SQLiteDestructiveQueryStore queries) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    CompletionStage<StartResult> start(
            StartRequest request,
            UUID operationId,
            Instant now) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(now, "now");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> start(transaction, request, operationId, now.toEpochMilli())));
    }

    private StartResult start(
            Connection connection,
            StartRequest request,
            UUID operationId,
            long now) throws SQLException {
        Optional<OperationView> existing = queries.findByIdempotencyKey(
                connection, request.idempotencyKey());
        if (existing.isPresent()) {
            return alreadyAccepted(existing.orElseThrow());
        }
        Optional<Preview> current = refreshedPreview(connection, request.preview());
        if (current.isEmpty()) {
            return missingStartResult(connection, request.preview().definitionId());
        }
        Preview refreshed = current.orElseThrow();
        StartResult rejected = validateConfirmation(connection, request.preview(), refreshed);
        if (rejected != null) {
            return rejected;
        }
        persistAcceptance(connection, request, operationId, refreshed, now);
        return StartResult.success(
                StartStatus.STARTED,
                queries.findOperation(connection, operationId).orElseThrow(),
                "The destructive intent and immutable target snapshot were committed.");
    }

    private Optional<Preview> refreshedPreview(Connection connection, Preview submitted)
            throws SQLException {
        return queries.preview(connection, new PreviewRequest(
                submitted.operationType(),
                submitted.definitionId(),
                submitted.exactInstanceId()));
    }

    private static StartResult alreadyAccepted(OperationView operation) {
        return StartResult.success(
                StartStatus.ALREADY_ACCEPTED,
                operation,
                "This destructive confirmation was already accepted idempotently.");
    }

    private static StartResult validateConfirmation(
            Connection connection,
            Preview submitted,
            Preview refreshed) throws SQLException {
        if (!refreshed.confirmationToken().equals(submitted.confirmationToken())) {
            return StartResult.failure(
                    StartStatus.STALE_CONFIRMATION,
                    "The definition, target set, queued work, or anomaly evidence changed; "
                            + "review a fresh confirmation summary.");
        }
        if (hasTargetConflict(connection, refreshed)) {
            return StartResult.failure(
                    StartStatus.TARGET_CONFLICT,
                    "At least one target already belongs to an unfinished destructive operation.");
        }
        return null;
    }

    private static void persistAcceptance(
            Connection connection,
            StartRequest request,
            UUID operationId,
            Preview preview,
            long now) throws SQLException {
        insertOperation(connection, request, operationId, preview, now);
        insertTargets(connection, operationId, preview, now);
        if (preview.operationType().deletesDefinition()
                && !markDefinitionDeleted(connection, preview, now)) {
            throw new SQLException("Definition changed after destructive confirmation validation");
        }
        SQLiteDestructiveControlStore.appendAudit(
                connection,
                operationId,
                "destructive_operation_accepted",
                request.actorId(),
                detailJson(preview, "accepted"),
                now);
        completeEmptyOperation(connection, operationId, preview.targetCount(), now);
    }

    private static boolean hasTargetConflict(Connection connection, Preview preview)
            throws SQLException {
        if (preview.exactInstanceId() == null) {
            return hasDefinitionTargetConflict(connection, preview);
        }
        return hasExactTargetConflict(connection, preview);
    }

    private static boolean hasDefinitionTargetConflict(Connection connection, Preview preview)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM lore_instances instance JOIN destructive_targets target "
                        + "ON target.instance_id = instance.instance_id "
                        + "WHERE instance.definition_id = ? AND instance.lifecycle_state = 'ACTIVE' "
                        + "AND target.state NOT IN ('COMPLETED', 'ABORTED') LIMIT 1")) {
            statement.setString(1, preview.definitionId().value().toString());
            return hasRow(statement);
        }
    }

    private static boolean hasExactTargetConflict(Connection connection, Preview preview)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM lore_instances instance JOIN destructive_targets target "
                        + "ON target.instance_id = instance.instance_id "
                        + "WHERE instance.definition_id = ? AND instance.instance_id = ? "
                        + "AND instance.lifecycle_state = 'ACTIVE' "
                        + "AND target.state NOT IN ('COMPLETED', 'ABORTED') LIMIT 1")) {
            statement.setString(1, preview.definitionId().value().toString());
            statement.setString(2, preview.exactInstanceId().value().toString());
            return hasRow(statement);
        }
    }

    private static boolean hasRow(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next();
        }
    }

    private static void insertOperation(
            Connection connection,
            StartRequest request,
            UUID operationId,
            Preview preview,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO destructive_operations(operation_id, operation_type, definition_id, "
                        + "exact_instance_id, expected_revision, state, actor_id, idempotency_key, "
                        + "confirmation_token, target_count, accepted_at, updated_at, terminal_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, NULL)")) {
            bindOperation(statement, request, operationId, preview, now);
            statement.executeUpdate();
        }
    }

    private static void bindOperation(
            PreparedStatement statement,
            StartRequest request,
            UUID operationId,
            Preview preview,
            long now) throws SQLException {
        statement.setString(1, operationId.toString());
        statement.setString(2, preview.operationType().name());
        statement.setString(3, preview.definitionId().value().toString());
        SQLiteDestructiveRows.setNullableString(
                statement,
                4,
                preview.exactInstanceId() == null
                        ? null
                        : preview.exactInstanceId().value().toString());
        statement.setLong(5, preview.expectedRevision().value());
        statement.setString(6, request.actorId());
        statement.setString(7, request.idempotencyKey());
        statement.setString(8, preview.confirmationToken());
        statement.setLong(9, preview.targetCount());
        statement.setLong(10, now);
        statement.setLong(11, now);
    }

    private static void insertTargets(
            Connection connection,
            UUID operationId,
            Preview preview,
            long now) throws SQLException {
        int inserted = preview.exactInstanceId() == null
                ? insertDefinitionTargets(connection, operationId, preview, now)
                : insertExactTarget(connection, operationId, preview, now);
        if (inserted != preview.targetCount()) {
            throw new SQLException("Destructive target snapshot changed during acceptance");
        }
    }

    private static int insertDefinitionTargets(
            Connection connection,
            UUID operationId,
            Preview preview,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                targetInsertSql(""))) {
            bindTargetInsert(statement, operationId, preview, now);
            return statement.executeUpdate();
        }
    }

    private static int insertExactTarget(
            Connection connection,
            UUID operationId,
            Preview preview,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                targetInsertSql(" AND instance.instance_id = ?"))) {
            bindTargetInsert(statement, operationId, preview, now);
            statement.setString(5, preview.exactInstanceId().value().toString());
            return statement.executeUpdate();
        }
    }

    private static String targetInsertSql(String exactClause) {
        return "INSERT INTO destructive_targets(operation_id, instance_id, definition_id, "
                + "expected_applied_revision, expected_location_type, expected_location_key, "
                + "expected_container_path, expected_fingerprint, state, effect_state, "
                + "claim_token, claim_expires_at, attempt_count, before_fingerprint, "
                + "after_fingerprint, last_error, created_at, updated_at) "
                + "SELECT ?, instance.instance_id, instance.definition_id, instance.applied_revision, "
                + "current.location_type, current.location_key, current.container_path, NULL, "
                + "'PENDING', 'UNKNOWN', NULL, NULL, 0, NULL, NULL, NULL, ?, ? "
                + "FROM lore_instances instance LEFT JOIN instance_current_state current "
                + "ON current.instance_id = instance.instance_id "
                + "WHERE instance.definition_id = ? AND instance.lifecycle_state = 'ACTIVE'"
                + exactClause;
    }

    private static void bindTargetInsert(
            PreparedStatement statement,
            UUID operationId,
            Preview preview,
            long now) throws SQLException {
        statement.setString(1, operationId.toString());
        statement.setLong(2, now);
        statement.setLong(3, now);
        statement.setString(4, preview.definitionId().value().toString());
    }

    private static boolean markDefinitionDeleted(
            Connection connection,
            Preview preview,
            long now) throws SQLException {
        if (!updateDeletedAt(connection, preview, now)) {
            return false;
        }
        try (PreparedStatement marker = connection.prepareStatement(
                "INSERT INTO deleted_definition_markers(definition_id, lookup_key, deleted_at) "
                        + "VALUES (?, ?, ?)")) {
            marker.setString(1, preview.definitionId().value().toString());
            marker.setString(2, preview.lookupKey().value());
            marker.setLong(3, now);
            marker.executeUpdate();
        }
        return true;
    }

    private static boolean updateDeletedAt(
            Connection connection,
            Preview preview,
            long now) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE lore_definitions SET deleted_at = ? WHERE definition_id = ? "
                        + "AND deleted_at IS NULL AND current_revision = ?")) {
            update.setLong(1, now);
            update.setString(2, preview.definitionId().value().toString());
            update.setLong(3, preview.expectedRevision().value());
            return update.executeUpdate() == SINGLE_ROW;
        }
    }

    private static StartResult missingStartResult(
            Connection connection,
            LoreDefinitionId definitionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT deleted_at FROM lore_definitions WHERE definition_id = ?")) {
            statement.setString(1, definitionId.value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return StartResult.failure(
                            StartStatus.NOT_FOUND,
                            "The lore-item definition no longer exists.");
                }
                resultSet.getLong(1);
                return resultSet.wasNull()
                        ? StartResult.failure(
                                StartStatus.REJECTED,
                                "The destructive target is no longer eligible.")
                        : StartResult.failure(
                                StartStatus.ALREADY_DELETED,
                                "The lore-item definition is already deleted.");
            }
        }
    }

    private static void completeEmptyOperation(
            Connection connection,
            UUID operationId,
            long targetCount,
            long now) throws SQLException {
        if (targetCount != 0L) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE destructive_operations SET state = 'COMPLETED', updated_at = ?, "
                        + "terminal_at = ? WHERE operation_id = ? AND state = 'ACTIVE'")) {
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.setString(3, operationId.toString());
            statement.executeUpdate();
        }
    }

    private static String detailJson(Preview preview, String action) {
        return "{\"action\":\"" + action
                + "\",\"operationType\":\"" + preview.operationType().name()
                + "\",\"definitionId\":\"" + preview.definitionId().value()
                + "\",\"targetCount\":" + preview.targetCount()
                + ",\"inaccessibleCount\":" + preview.inaccessibleCount()
                + ",\"queuedCount\":" + preview.queuedCount()
                + ",\"anomalyCount\":" + preview.anomalyCount() + '}';
    }
}