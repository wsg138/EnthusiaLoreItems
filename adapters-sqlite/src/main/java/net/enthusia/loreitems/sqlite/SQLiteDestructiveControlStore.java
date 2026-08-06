package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ControlRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ControlResult;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ControlStatus;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.OperationView;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ReviewRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ReviewResolution;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ReviewResult;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ReviewStatus;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.TargetView;
import net.enthusia.loreitems.domain.DestructiveEffectState;
import net.enthusia.loreitems.domain.DestructiveOperationState;
import net.enthusia.loreitems.domain.DestructiveTargetState;
import net.enthusia.loreitems.domain.LoreInstanceId;

final class SQLiteDestructiveControlStore {
    private static final int SINGLE_ROW = 1;
    private final SQLiteStorageRuntime storage;
    private final SQLiteDestructiveQueryStore queries;

    SQLiteDestructiveControlStore(
            SQLiteStorageRuntime storage,
            SQLiteDestructiveQueryStore queries) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    CompletionStage<ControlResult> control(
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

    CompletionStage<ReviewResult> resolveReview(ReviewRequest request, Instant now) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(now, "now");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> resolveReview(transaction, request, now.toEpochMilli())));
    }

    private ControlResult control(
            Connection connection,
            ControlRequest request,
            DestructiveOperationState target,
            long now) throws SQLException {
        Optional<OperationView> found = queries.findOperation(connection, request.operationId());
        ControlResult terminal = validateControl(found, target);
        if (terminal != null) {
            return terminal;
        }
        OperationView operation = found.orElseThrow();
        if (!updateOperationState(connection, operation, target, now)) {
            return rejectedControl();
        }
        appendAudit(
                connection,
                request.operationId(),
                target == DestructiveOperationState.PAUSED
                        ? "destructive_operation_paused"
                        : "destructive_operation_resumed",
                request.actorId(),
                "{\"state\":\"" + target.name() + "\"}",
                now);
        return updatedControl(connection, request.operationId(), target);
    }

    private static ControlResult validateControl(
            Optional<OperationView> found,
            DestructiveOperationState target) {
        if (found.isEmpty()) {
            return new ControlResult(
                    ControlStatus.NOT_FOUND,
                    null,
                    "The destructive operation was not found.");
        }
        OperationView operation = found.orElseThrow();
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
                    "The destructive operation is already " + stateName(target) + '.');
        }
        operation.state().transitionTo(target);
        return null;
    }

    private static boolean updateOperationState(
            Connection connection,
            OperationView operation,
            DestructiveOperationState target,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE destructive_operations SET state = ?, updated_at = ? "
                        + "WHERE operation_id = ? AND state = ?")) {
            statement.setString(1, target.name());
            statement.setLong(2, now);
            statement.setString(3, operation.operationId().toString());
            statement.setString(4, operation.state().name());
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    private ControlResult updatedControl(
            Connection connection,
            UUID operationId,
            DestructiveOperationState target) throws SQLException {
        return new ControlResult(
                ControlStatus.UPDATED,
                queries.findOperation(connection, operationId).orElseThrow(),
                "The destructive operation is now " + stateName(target) + '.');
    }

    private static ControlResult rejectedControl() {
        return new ControlResult(
                ControlStatus.REJECTED,
                null,
                "The destructive operation changed before control was applied.");
    }

    private ReviewResult resolveReview(
            Connection connection,
            ReviewRequest request,
            long now) throws SQLException {
        TargetReviewSnapshot target = readReviewTarget(
                connection, request.operationId(), request.instanceId());
        ReviewResult invalid = validateReview(target, request.resolution());
        if (invalid != null) {
            return invalid;
        }
        DestructiveTargetState nextState = targetState(request.resolution());
        DestructiveEffectState nextEffect = targetEffect(request.resolution());
        if (!updateReviewedTarget(connection, request, nextState, nextEffect, now)) {
            return rejectedReview();
        }
        finishReview(connection, request, nextState, now);
        TargetView updated = queries.findTarget(
                connection, request.operationId(), request.instanceId()).orElseThrow();
        return new ReviewResult(
                ReviewStatus.RESOLVED,
                updated,
                "The evidence-gated destructive review resolution was committed.");
    }

    private static ReviewResult validateReview(
            TargetReviewSnapshot target,
            ReviewResolution resolution) {
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
        if (!evidenceAllows(resolution, target.effectState())) {
            return new ReviewResult(
                    ReviewStatus.EVIDENCE_MISMATCH,
                    null,
                    "The persisted physical-effect evidence does not permit that resolution.");
        }
        return null;
    }

    private static void finishReview(
            Connection connection,
            ReviewRequest request,
            DestructiveTargetState nextState,
            long now) throws SQLException {
        if (nextState == DestructiveTargetState.COMPLETED) {
            markInstanceRemoved(connection, request.instanceId(), now);
        }
        appendAudit(
                connection,
                request.operationId(),
                reviewAuditEvent(request.resolution()),
                request.actorId(),
                "{\"instanceId\":\"" + request.instanceId().value()
                        + "\",\"evidence\":\"" + escapeJson(request.evidenceDetail()) + "\"}",
                now);
        refreshParentTerminalState(connection, request.operationId(), now);
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

    private static DestructiveEffectState targetEffect(ReviewResolution resolution) {
        return switch (resolution) {
            case REQUEUE_NO_SIDE_EFFECT, ABORT_NO_SIDE_EFFECT ->
                    DestructiveEffectState.NONE_OBSERVED;
            case MARK_VERIFIED_REMOVED -> DestructiveEffectState.REMOVED_OBSERVED;
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
        updateParentTerminalState(connection, operationId, terminal, now);
    }

    private static ParentCounts parentCounts(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT SUM(CASE WHEN state NOT IN ('COMPLETED', 'ABORTED') THEN 1 ELSE 0 END) "
                        + "nonterminal_count, SUM(CASE WHEN state = 'ABORTED' THEN 1 ELSE 0 END) "
                        + "aborted_count FROM destructive_targets WHERE operation_id = ?")) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? new ParentCounts(
                                resultSet.getLong("nonterminal_count"),
                                resultSet.getLong("aborted_count"))
                        : null;
            }
        }
    }

    private static void updateParentTerminalState(
            Connection connection,
            UUID operationId,
            DestructiveOperationState terminal,
            long now) throws SQLException {
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

    static void appendAudit(
            Connection connection,
            UUID operationId,
            String eventType,
            String actorId,
            String detailJson,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO audit_events(aggregate_type, aggregate_id, event_type, actor_type, "
                        + "actor_id, detail_json, occurred_at) "
                        + "VALUES ('destructive_operation', ?, ?, 'STAFF', ?, ?, ?)")) {
            statement.setString(1, operationId.toString());
            statement.setString(2, eventType);
            statement.setString(3, actorId);
            statement.setString(4, detailJson);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    private static ReviewResult rejectedReview() {
        return new ReviewResult(
                ReviewStatus.REJECTED,
                null,
                "The destructive target changed before review resolution was committed.");
    }

    private static String stateName(DestructiveOperationState state) {
        return state.name().toLowerCase(Locale.ROOT);
    }

    static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> appendJsonCharacter(escaped, character);
            }
        }
        return escaped.toString();
    }

    private static void appendJsonCharacter(StringBuilder escaped, char character) {
        if (character >= ' ') {
            escaped.append(character);
            return;
        }
        String hexadecimal = Integer.toHexString(character);
        escaped.append("\\u");
        escaped.append("0000", 0, 4 - hexadecimal.length());
        escaped.append(hexadecimal);
    }

    private record TargetReviewSnapshot(
            DestructiveTargetState state,
            DestructiveEffectState effectState) {}

    private record ParentCounts(long nonTerminal, long aborted) {}
}
