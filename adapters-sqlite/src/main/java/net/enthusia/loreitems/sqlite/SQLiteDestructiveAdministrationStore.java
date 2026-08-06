package net.enthusia.loreitems.sqlite;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ControlRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ControlResult;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ControlStatus;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.Metrics;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.OperationView;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.Preview;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.PreviewRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ReviewRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ReviewResolution;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ReviewResult;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ReviewStatus;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.StartRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.StartResult;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.StartStatus;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.TargetView;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.DestructiveEffectState;
import net.enthusia.loreitems.domain.DestructiveOperationState;
import net.enthusia.loreitems.domain.DestructiveOperationType;
import net.enthusia.loreitems.domain.DestructiveTargetState;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;

final class SQLiteDestructiveAdministrationStore {
    private static final int SINGLE_ROW = 1;
    private static final String OPERATION_SELECT = "SELECT operation.*, "
            + "SUM(CASE WHEN target.state = 'PENDING' THEN 1 ELSE 0 END) pending_count, "
            + "SUM(CASE WHEN target.state IN ('CLAIMED', 'APPLIED', 'VERIFIED') "
            + "THEN 1 ELSE 0 END) claimed_count, "
            + "SUM(CASE WHEN target.state = 'REVIEW_REQUIRED' THEN 1 ELSE 0 END) review_count, "
            + "SUM(CASE WHEN target.state = 'COMPLETED' THEN 1 ELSE 0 END) completed_count, "
            + "SUM(CASE WHEN target.state = 'ABORTED' THEN 1 ELSE 0 END) aborted_count "
            + "FROM destructive_operations operation "
            + "LEFT JOIN destructive_targets target "
            + "ON target.operation_id = operation.operation_id ";
    private static final String OPERATION_GROUP = " GROUP BY operation.operation_id ";

    private final SQLiteStorageRuntime storage;

    SQLiteDestructiveAdministrationStore(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    CompletionStage<Optional<Preview>> preview(PreviewRequest request) {
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> preview(connection, request));
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

    CompletionStage<Page<OperationView>> listOperations(PageRequest request) {
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> listOperations(connection, request));
    }

    CompletionStage<Page<TargetView>> listTargets(UUID operationId, PageRequest request) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> listTargets(connection, operationId, request));
    }

    CompletionStage<ControlResult> pause(ControlRequest request, Instant now) {
        return control(request, DestructiveOperationState.PAUSED, now);
    }

    CompletionStage<ControlResult> resume(ControlRequest request, Instant now) {
        return control(request, DestructiveOperationState.ACTIVE, now);
    }

    CompletionStage<ReviewResult> resolveReview(ReviewRequest request, Instant now) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(now, "now");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> resolveReview(transaction, request, now.toEpochMilli())));
    }

    CompletionStage<Metrics> metrics(Instant now) {
        Objects.requireNonNull(now, "now");
        return storage.execute(connection -> metrics(connection, now.toEpochMilli()));
    }

    private CompletionStage<ControlResult> control(
            ControlRequest request,
            DestructiveOperationState target,
            Instant now) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(now, "now");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> control(transaction, request, target, now.toEpochMilli())));
    }

    private static StartResult start(
            Connection connection,
            StartRequest request,
            UUID operationId,
            long now) throws SQLException {
        Optional<OperationView> existing = findByIdempotencyKey(
                connection, request.idempotencyKey());
        if (existing.isPresent()) {
            return StartResult.success(
                    StartStatus.ALREADY_ACCEPTED,
                    existing.orElseThrow(),
                    "This destructive confirmation was already accepted idempotently.");
        }
        Optional<Preview> current = preview(connection, new PreviewRequest(
                request.preview().operationType(),
                request.preview().definitionId(),
                request.preview().exactInstanceId()));
        if (current.isEmpty()) {
            return missingStartResult(connection, request.preview().definitionId());
        }
        Preview refreshed = current.orElseThrow();
        if (!refreshed.confirmationToken().equals(request.preview().confirmationToken())) {
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
        insertOperation(connection, request, operationId, refreshed, now);
        insertTargets(connection, operationId, refreshed, now);
        if (refreshed.operationType().deletesDefinition()
                && !markDefinitionDeleted(connection, refreshed, now)) {
            throw new SQLException("Definition changed after destructive confirmation validation");
        }
        appendAudit(
                connection,
                "destructive_operation",
                operationId.toString(),
                "destructive_operation_accepted",
                request.actorId(),
                detailJson(refreshed, "accepted"),
                now);
        completeEmptyOperation(connection, operationId, refreshed.targetCount(), now);
        return StartResult.success(
                StartStatus.STARTED,
                findOperation(connection, operationId).orElseThrow(),
                "The destructive intent and immutable target snapshot were committed.");
    }

    private static Optional<Preview> preview(
            Connection connection,
            PreviewRequest request) throws SQLException {
        DefinitionSnapshot definition = readDefinitionSnapshot(connection, request);
        if (definition == null || definition.deleted()) {
            return Optional.empty();
        }
        TargetCounts counts = readTargetCounts(connection, request);
        if (request.operationType().exactInstanceRequired() && counts.targetCount() != 1L) {
            return Optional.empty();
        }
        long queued = countQueuedWork(connection, request);
        long anomalies = countAnomalies(connection, request);
        String token = confirmationToken(request, definition, counts, queued, anomalies);
        return Optional.of(new Preview(
                request.operationType(),
                request.definitionId(),
                definition.lookupKey(),
                definition.displayName(),
                definition.currentRevision(),
                request.exactInstanceId(),
                counts.targetCount(),
                counts.inaccessibleCount(),
                queued,
                anomalies,
                token));
    }

    private static DefinitionSnapshot readDefinitionSnapshot(
            Connection connection,
            PreviewRequest request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT lookup_key, display_name, current_revision, deleted_at "
                        + "FROM lore_definitions WHERE definition_id = ?")) {
            statement.setString(1, request.definitionId().value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                resultSet.getLong("deleted_at");
                boolean deleted = !resultSet.wasNull();
                return new DefinitionSnapshot(
                        new DefinitionKey(resultSet.getString("lookup_key")),
                        resultSet.getString("display_name"),
                        new TemplateRevision(resultSet.getLong("current_revision")),
                        deleted);
            }
        }
    }

    private static TargetCounts readTargetCounts(
            Connection connection,
            PreviewRequest request) throws SQLException {
        String exactClause = request.exactInstanceId() == null
                ? ""
                : " AND instance.instance_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) target_count, "
                        + "SUM(CASE WHEN current.state IS NULL "
                        + "OR current.state <> 'CONFIRMED_NOW' THEN 1 ELSE 0 END) inaccessible_count "
                        + "FROM lore_instances instance "
                        + "LEFT JOIN instance_current_state current "
                        + "ON current.instance_id = instance.instance_id "
                        + "WHERE instance.definition_id = ? "
                        + "AND instance.lifecycle_state = 'ACTIVE'" + exactClause)) {
            statement.setString(1, request.definitionId().value().toString());
            if (request.exactInstanceId() != null) {
                statement.setString(2, request.exactInstanceId().value().toString());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return new TargetCounts(
                        resultSet.getLong("target_count"),
                        resultSet.getLong("inaccessible_count"));
            }
        }
    }

    private static long countQueuedWork(
            Connection connection,
            PreviewRequest request) throws SQLException {
        String exactClause = request.exactInstanceId() == null
                ? ""
                : " AND mutation.instance_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM pending_mutations mutation "
                        + "WHERE mutation.definition_id = ? "
                        + "AND mutation.state NOT IN ('COMPLETED', 'CANCELLED')" + exactClause)) {
            statement.setString(1, request.definitionId().value().toString());
            if (request.exactInstanceId() != null) {
                statement.setString(2, request.exactInstanceId().value().toString());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static long countAnomalies(
            Connection connection,
            PreviewRequest request) throws SQLException {
        String exactClause = request.exactInstanceId() == null
                ? ""
                : " AND anomaly.instance_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM instance_anomalies anomaly "
                        + "WHERE anomaly.definition_id = ? "
                        + "AND anomaly.status IN ('OPEN', 'ACKNOWLEDGED')" + exactClause)) {
            statement.setString(1, request.definitionId().value().toString());
            if (request.exactInstanceId() != null) {
                statement.setString(2, request.exactInstanceId().value().toString());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static boolean hasTargetConflict(Connection connection, Preview preview)
            throws SQLException {
        String exactClause = preview.exactInstanceId() == null
                ? ""
                : " AND instance.instance_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM lore_instances instance "
                        + "JOIN destructive_targets target "
                        + "ON target.instance_id = instance.instance_id "
                        + "WHERE instance.definition_id = ? "
                        + "AND instance.lifecycle_state = 'ACTIVE' "
                        + "AND target.state NOT IN ('COMPLETED', 'ABORTED')"
                        + exactClause + " LIMIT 1")) {
            statement.setString(1, preview.definitionId().value().toString());
            if (preview.exactInstanceId() != null) {
                statement.setString(2, preview.exactInstanceId().value().toString());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
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
            statement.executeUpdate();
        }
    }

    private static void insertTargets(
            Connection connection,
            UUID operationId,
            Preview preview,
            long now) throws SQLException {
        String exactClause = preview.exactInstanceId() == null
                ? ""
                : " AND instance.instance_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO destructive_targets(operation_id, instance_id, definition_id, "
                        + "expected_applied_revision, expected_location_type, expected_location_key, "
                        + "expected_container_path, expected_fingerprint, state, effect_state, "
                        + "claim_token, claim_expires_at, attempt_count, before_fingerprint, "
                        + "after_fingerprint, last_error, created_at, updated_at) "
                        + "SELECT ?, instance.instance_id, instance.definition_id, "
                        + "instance.applied_revision, current.location_type, current.location_key, "
                        + "current.container_path, NULL, 'PENDING', 'UNKNOWN', NULL, NULL, 0, "
                        + "NULL, NULL, NULL, ?, ? FROM lore_instances instance "
                        + "LEFT JOIN instance_current_state current "
                        + "ON current.instance_id = instance.instance_id "
                        + "WHERE instance.definition_id = ? "
                        + "AND instance.lifecycle_state = 'ACTIVE'" + exactClause)) {
            statement.setString(1, operationId.toString());
            statement.setLong(2, now);
            statement.setLong(3, now);
            statement.setString(4, preview.definitionId().value().toString());
            if (preview.exactInstanceId() != null) {
                statement.setString(5, preview.exactInstanceId().value().toString());
            }
            int inserted = statement.executeUpdate();
            if (inserted != preview.targetCount()) {
                throw new SQLException("Destructive target snapshot changed during acceptance");
            }
        }
    }

    private static boolean markDefinitionDeleted(
            Connection connection,
            Preview preview,
            long now) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE lore_definitions SET deleted_at = ? "
                        + "WHERE definition_id = ? AND deleted_at IS NULL AND current_revision = ?")) {
            update.setLong(1, now);
            update.setString(2, preview.definitionId().value().toString());
            update.setLong(3, preview.expectedRevision().value());
            if (update.executeUpdate() != SINGLE_ROW) {
                return false;
            }
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
                if (!resultSet.wasNull()) {
                    return StartResult.failure(
                            StartStatus.ALREADY_DELETED,
                            "The lore-item definition is already deleted.");
                }
            }
        }
        return StartResult.failure(
                StartStatus.REJECTED,
                "The destructive target is no longer eligible.");
    }

    private static ControlResult control(
            Connection connection,
            ControlRequest request,
            DestructiveOperationState target,
            long now) throws SQLException {
        Optional<OperationView> current = findOperation(connection, request.operationId());
        if (current.isEmpty()) {
            return new ControlResult(
                    ControlStatus.NOT_FOUND,
                    null,
                    "The destructive operation was not found.");
        }
        OperationView operation = current.orElseThrow();
        if (operation.state().terminal()) {
            return new ControlResult(
                    ControlStatus.TERMINAL,
                    null,
                    "Terminal destructive operations cannot be paused or resumed.");
        }
        if (operation.state() == target) {
            return new ControlResult(
                    ControlStatus.ALREADY_IN_STATE,
                    operation,
                    "The destructive operation is already "
                            + target.name().toLowerCase(Locale.ROOT) + '.');
        }
        operation.state().transitionTo(target);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE destructive_operations SET state = ?, updated_at = ? "
                        + "WHERE operation_id = ? AND state = ?")) {
            statement.setString(1, target.name());
            statement.setLong(2, now);
            statement.setString(3, request.operationId().toString());
            statement.setString(4, operation.state().name());
            if (statement.executeUpdate() != SINGLE_ROW) {
                return new ControlResult(
                        ControlStatus.REJECTED,
                        null,
                        "The destructive operation changed before control was applied.");
            }
        }
        appendAudit(
                connection,
                "destructive_operation",
                request.operationId().toString(),
                target == DestructiveOperationState.PAUSED
                        ? "destructive_operation_paused"
                        : "destructive_operation_resumed",
                request.actorId(),
                "{\"state\":\"" + target.name() + "\"}",
                now);
        return new ControlResult(
                ControlStatus.UPDATED,
                findOperation(connection, request.operationId()).orElseThrow(),
                "The destructive operation is now "
                        + target.name().toLowerCase(Locale.ROOT) + '.');
    }

    private static ReviewResult resolveReview(
            Connection connection,
            ReviewRequest request,
            long now) throws SQLException {
        TargetReviewSnapshot target = readReviewTarget(
                connection, request.operationId(), request.instanceId());
        if (target == null) {
            return new ReviewResult(
                    ReviewStatus.NOT_FOUND,
                    null,
                    "The destructive review target was not found.");
        }
        if (target.state() != DestructiveTargetState.REVIEW_REQUIRED) {
            return new ReviewResult(
                    ReviewStatus.NOT_REVIEW_REQUIRED,
                    null,
                    "The destructive target is not waiting for review.");
        }
        if (!evidenceAllows(request.resolution(), target.effectState())) {
            return new ReviewResult(
                    ReviewStatus.EVIDENCE_MISMATCH,
                    null,
                    "The persisted physical-effect evidence does not permit that resolution.");
        }
        DestructiveTargetState nextState = targetState(request.resolution());
        DestructiveEffectState nextEffect = request.resolution()
                        == ReviewResolution.MARK_VERIFIED_REMOVED
                ? DestructiveEffectState.REMOVED_OBSERVED
                : DestructiveEffectState.UNKNOWN;
        if (!updateReviewedTarget(connection, request, nextState, nextEffect, now)) {
            return new ReviewResult(
                    ReviewStatus.REJECTED,
                    null,
                    "The destructive target changed before review resolution was committed.");
        }
        if (nextState == DestructiveTargetState.COMPLETED) {
            markInstanceRemoved(connection, request.instanceId(), now);
        }
        appendAudit(
                connection,
                "destructive_operation",
                request.operationId().toString(),
                reviewAuditEvent(request.resolution()),
                request.actorId(),
                "{\"instanceId\":\"" + request.instanceId().value()
                        + "\",\"evidence\":\"" + escapeJson(request.evidenceDetail()) + "\"}",
                now);
        refreshParentTerminalState(connection, request.operationId(), now);
        TargetView updated = findTarget(
                connection, request.operationId(), request.instanceId()).orElseThrow();
        return new ReviewResult(
                ReviewStatus.RESOLVED,
                updated,
                "The evidence-gated destructive review resolution was committed.");
    }

    private static boolean evidenceAllows(
            ReviewResolution resolution,
            DestructiveEffectState effectState) {
        return switch (resolution) {
            case REQUEUE_NO_SIDE_EFFECT, ABORT_NO_SIDE_EFFECT ->
                    effectState == DestructiveEffectState.NONE_OBSERVED;
            case MARK_VERIFIED_REMOVED ->
                    effectState == DestructiveEffectState.REMOVED_OBSERVED;
        };
    }

    private static DestructiveTargetState targetState(ReviewResolution resolution) {
        return switch (resolution) {
            case REQUEUE_NO_SIDE_EFFECT -> DestructiveTargetState.PENDING;
            case MARK_VERIFIED_REMOVED -> DestructiveTargetState.COMPLETED;
            case ABORT_NO_SIDE_EFFECT -> DestructiveTargetState.ABORTED;
        };
    }

    private static String reviewAuditEvent(ReviewResolution resolution) {
        return switch (resolution) {
            case REQUEUE_NO_SIDE_EFFECT -> "destructive_target_requeued";
            case MARK_VERIFIED_REMOVED -> "destructive_target_marked_verified";
            case ABORT_NO_SIDE_EFFECT -> "destructive_target_aborted";
        };
    }

    private static boolean updateReviewedTarget(
            Connection connection,
            ReviewRequest request,
            DestructiveTargetState nextState,
            DestructiveEffectState nextEffect,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE destructive_targets SET state = ?, effect_state = ?, claim_token = NULL, "
                        + "claim_expires_at = NULL, last_error = ?, updated_at = ? "
                        + "WHERE operation_id = ? AND instance_id = ? "
                        + "AND state = 'REVIEW_REQUIRED'")) {
            statement.setString(1, nextState.name());
            statement.setString(2, nextEffect.name());
            statement.setString(3, request.evidenceDetail());
            statement.setLong(4, now);
            statement.setString(5, request.operationId().toString());
            statement.setString(6, request.instanceId().value().toString());
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    private static TargetReviewSnapshot readReviewTarget(
            Connection connection,
            UUID operationId,
            LoreInstanceId instanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state, effect_state FROM destructive_targets "
                        + "WHERE operation_id = ? AND instance_id = ?")) {
            statement.setString(1, operationId.toString());
            statement.setString(2, instanceId.value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? new TargetReviewSnapshot(
                                DestructiveTargetState.valueOf(resultSet.getString("state")),
                                DestructiveEffectState.valueOf(resultSet.getString("effect_state")))
                        : null;
            }
        }
    }

    private static void markInstanceRemoved(
            Connection connection,
            LoreInstanceId instanceId,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE lore_instances SET lifecycle_state = 'REMOVED', terminal_at = ? "
                        + "WHERE instance_id = ? AND lifecycle_state = 'ACTIVE'")) {
            statement.setLong(1, now);
            statement.setString(2, instanceId.value().toString());
            statement.executeUpdate();
        }
    }

    static void refreshParentTerminalState(
            Connection connection,
            UUID operationId,
            long now) throws SQLException {
        ParentCounts counts = parentCounts(connection, operationId);
        if (counts == null || counts.nonTerminal() > 0L) {
            return;
        }
        DestructiveOperationState terminal = counts.aborted() > 0L
                ? DestructiveOperationState.ABORTED
                : DestructiveOperationState.COMPLETED;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE destructive_operations SET state = ?, updated_at = ?, terminal_at = ? "
                        + "WHERE operation_id = ? AND state IN ('ACTIVE', 'PAUSED')")) {
            statement.setString(1, terminal.name());
            statement.setLong(2, now);
            statement.setLong(3, now);
            statement.setString(4, operationId.toString());
            statement.executeUpdate();
        }
    }

    private static ParentCounts parentCounts(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT SUM(CASE WHEN state NOT IN ('COMPLETED', 'ABORTED') THEN 1 ELSE 0 END) "
                        + "nonterminal_count, "
                        + "SUM(CASE WHEN state = 'ABORTED' THEN 1 ELSE 0 END) aborted_count "
                        + "FROM destructive_targets WHERE operation_id = ?")) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new ParentCounts(
                        resultSet.getLong("nonterminal_count"),
                        resultSet.getLong("aborted_count"));
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

    private static Page<OperationView> listOperations(
            Connection connection,
            PageRequest request) throws SQLException {
        List<OperationView> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                OPERATION_SELECT + OPERATION_GROUP
                        + "ORDER BY operation.accepted_at DESC, operation.operation_id "
                        + "LIMIT ? OFFSET ?")) {
            statement.setInt(1, request.limit() + 1);
            statement.setInt(2, request.offset());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    values.add(SQLiteDestructiveRows.readOperation(resultSet));
                }
            }
        }
        return page(values, request);
    }

    private static Page<TargetView> listTargets(
            Connection connection,
            UUID operationId,
            PageRequest request) throws SQLException {
        List<TargetView> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM destructive_targets WHERE operation_id = ? "
                        + "ORDER BY created_at, instance_id LIMIT ? OFFSET ?")) {
            statement.setString(1, operationId.toString());
            statement.setInt(2, request.limit() + 1);
            statement.setInt(3, request.offset());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    values.add(SQLiteDestructiveRows.readTarget(resultSet));
                }
            }
        }
        return page(values, request);
    }

    private static Optional<OperationView> findByIdempotencyKey(
            Connection connection,
            String idempotencyKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                OPERATION_SELECT
                        + "WHERE operation.idempotency_key = ?"
                        + OPERATION_GROUP)) {
            statement.setString(1, idempotencyKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(SQLiteDestructiveRows.readOperation(resultSet))
                        : Optional.empty();
            }
        }
    }

    static Optional<OperationView> findOperation(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                OPERATION_SELECT
                        + "WHERE operation.operation_id = ?"
                        + OPERATION_GROUP)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(SQLiteDestructiveRows.readOperation(resultSet))
                        : Optional.empty();
            }
        }
    }

    static Optional<TargetView> findTarget(
            Connection connection,
            UUID operationId,
            LoreInstanceId instanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM destructive_targets WHERE operation_id = ? AND instance_id = ?")) {
            statement.setString(1, operationId.toString());
            statement.setString(2, instanceId.value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(SQLiteDestructiveRows.readTarget(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static Metrics metrics(Connection connection, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT "
                        + "(SELECT COUNT(*) FROM destructive_operations WHERE state = 'ACTIVE') "
                        + "active_operations, "
                        + "(SELECT COUNT(*) FROM destructive_operations WHERE state = 'PAUSED') "
                        + "paused_operations, "
                        + "(SELECT COUNT(*) FROM destructive_targets WHERE state = 'PENDING') "
                        + "queued_targets, "
                        + "(SELECT COUNT(*) FROM destructive_targets WHERE state = 'CLAIMED') "
                        + "active_leases, "
                        + "(SELECT COUNT(*) FROM destructive_targets "
                        + "WHERE state = 'REVIEW_REQUIRED') review_targets, "
                        + "(SELECT MIN(created_at) FROM destructive_targets "
                        + "WHERE state = 'PENDING') oldest_created, "
                        + "(SELECT COALESCE(SUM(attempt_count), 0) FROM destructive_targets) "
                        + "total_attempts")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                long oldest = resultSet.getLong("oldest_created");
                long age = resultSet.wasNull() ? 0L : Math.max(0L, now - oldest);
                return new Metrics(
                        resultSet.getLong("active_operations"),
                        resultSet.getLong("paused_operations"),
                        resultSet.getLong("queued_targets"),
                        resultSet.getLong("active_leases"),
                        resultSet.getLong("review_targets"),
                        age,
                        resultSet.getLong("total_attempts"));
            }
        }
    }

    private static String confirmationToken(
            PreviewRequest request,
            DefinitionSnapshot definition,
            TargetCounts counts,
            long queued,
            long anomalies) {
        String material = request.operationType().name()
                + '|' + request.definitionId().value()
                + '|' + (request.exactInstanceId() == null
                        ? "-"
                        : request.exactInstanceId().value())
                + '|' + definition.lookupKey().value()
                + '|' + definition.currentRevision().value()
                + '|' + counts.targetCount()
                + '|' + counts.inaccessibleCount()
                + '|' + queued
                + '|' + anomalies;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by Java", exception);
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

    static void appendAudit(
            Connection connection,
            String aggregateType,
            String aggregateId,
            String eventType,
            String actorId,
            String detailJson,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO audit_events(aggregate_type, aggregate_id, event_type, actor_type, "
                        + "actor_id, detail_json, occurred_at) VALUES (?, ?, ?, 'STAFF', ?, ?, ?)")) {
            statement.setString(1, aggregateType);
            statement.setString(2, aggregateId);
            statement.setString(3, eventType);
            statement.setString(4, actorId);
            statement.setString(5, detailJson);
            statement.setLong(6, now);
            statement.executeUpdate();
        }
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static <T> Page<T> page(List<T> values, PageRequest request) {
        boolean hasMore = values.size() > request.limit();
        if (hasMore) {
            values.remove(values.size() - 1);
        }
        return new Page<>(values, request.offset(), request.limit(), hasMore);
    }

    private record DefinitionSnapshot(
            DefinitionKey lookupKey,
            String displayName,
            TemplateRevision currentRevision,
            boolean deleted) {}

    private record TargetCounts(long targetCount, long inaccessibleCount) {}

    private record TargetReviewSnapshot(
            DestructiveTargetState state,
            DestructiveEffectState effectState) {}

    private record ParentCounts(long nonTerminal, long aborted) {}
}