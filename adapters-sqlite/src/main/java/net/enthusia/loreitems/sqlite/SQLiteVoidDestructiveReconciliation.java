package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.enthusia.loreitems.application.PreparedVoidLoss;

final class SQLiteVoidDestructiveReconciliation {
    private static final int SINGLE_ROW = 1;
    private static final int MAX_ERROR_LENGTH = 2_000;
    private static final String EXPIRED_VOID_DETAIL =
            "A terminal-void claim expired; physical outcome is unknown.";

    private SQLiteVoidDestructiveReconciliation() {
    }

    static void completePendingTarget(
            Connection connection,
            PreparedVoidLoss loss,
            long completedAt) throws SQLException {
        UUID operationId = findPendingOperation(connection, loss);
        if (operationId == null) {
            return;
        }
        if (!markCompleted(connection, operationId, loss, completedAt)) {
            throw new SQLException(
                    "Pending destructive target changed during terminal-void reconciliation");
        }
        appendAudit(connection, operationId, loss.identity().instanceId().value(),
                "REMOVED_OBSERVED", completedAt);
        SQLiteDestructiveControlStore.refreshParentTerminalState(
                connection, operationId, completedAt);
    }

    static void reviewPendingTarget(
            Connection connection,
            PreparedVoidLoss loss,
            String reason,
            long reviewedAt) throws SQLException {
        UUID operationId = findPendingOperation(connection, loss);
        if (operationId == null) {
            return;
        }
        if (!markReviewRequired(
                connection,
                operationId,
                loss.identity().instanceId().value(),
                loss.identity().definitionId().value(),
                reason,
                reviewedAt)) {
            throw new SQLException(
                    "Pending destructive target changed during terminal-void review reconciliation");
        }
        appendAudit(connection, operationId, loss.identity().instanceId().value(),
                "AMBIGUOUS", reviewedAt);
    }

    static void reviewPendingTargetsForVoidReviews(
            Connection connection,
            long reviewedAt,
            int limit) throws SQLException {
        List<PendingTarget> targets = findPendingTargetsForVoidReviews(connection, limit);
        for (PendingTarget target : targets) {
            if (!markReviewRequired(
                    connection,
                    target.operationId(),
                    target.instanceId(),
                    target.definitionId(),
                    EXPIRED_VOID_DETAIL,
                    reviewedAt)) {
                throw new SQLException(
                        "Pending destructive target changed during expired void-claim reconciliation");
            }
            appendAudit(connection, target.operationId(), target.instanceId(),
                    "AMBIGUOUS", reviewedAt);
        }
    }

    private static UUID findPendingOperation(
            Connection connection,
            PreparedVoidLoss loss) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT target.operation_id FROM destructive_targets target "
                        + "JOIN destructive_operations operation "
                        + "ON operation.operation_id = target.operation_id "
                        + "WHERE target.instance_id = ? AND target.definition_id = ? "
                        + "AND target.state = 'PENDING' "
                        + "AND operation.state IN ('ACTIVE', 'PAUSED') LIMIT 1")) {
            statement.setString(1, loss.identity().instanceId().value().toString());
            statement.setString(2, loss.identity().definitionId().value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? UUID.fromString(resultSet.getString("operation_id"))
                        : null;
            }
        }
    }

    private static List<PendingTarget> findPendingTargetsForVoidReviews(
            Connection connection,
            int limit) throws SQLException {
        List<PendingTarget> targets = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT target.operation_id, target.instance_id, target.definition_id "
                        + "FROM destructive_targets target "
                        + "JOIN destructive_operations operation "
                        + "ON operation.operation_id = target.operation_id "
                        + "WHERE target.state = 'PENDING' "
                        + "AND operation.state IN ('ACTIVE', 'PAUSED') "
                        + "AND EXISTS (SELECT 1 FROM pending_mutations mutation "
                        + "WHERE mutation.mutation_type = 'VOID_TERMINAL_LOSS' "
                        + "AND mutation.state = 'REVIEW_REQUIRED' "
                        + "AND mutation.instance_id = target.instance_id "
                        + "AND mutation.definition_id = target.definition_id) "
                        + "ORDER BY target.updated_at, target.operation_id, target.instance_id "
                        + "LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    targets.add(new PendingTarget(
                            UUID.fromString(resultSet.getString("operation_id")),
                            UUID.fromString(resultSet.getString("instance_id")),
                            UUID.fromString(resultSet.getString("definition_id"))));
                }
            }
        }
        return targets;
    }

    private static boolean markCompleted(
            Connection connection,
            UUID operationId,
            PreparedVoidLoss loss,
            long completedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE destructive_targets SET state = 'COMPLETED', "
                        + "effect_state = 'REMOVED_OBSERVED', claim_token = NULL, "
                        + "claim_expires_at = NULL, before_fingerprint = NULL, "
                        + "after_fingerprint = NULL, last_error = NULL, updated_at = ? "
                        + "WHERE operation_id = ? AND instance_id = ? "
                        + "AND definition_id = ? AND state = 'PENDING'")) {
            statement.setLong(1, completedAt);
            bindIdentity(
                    statement,
                    2,
                    operationId,
                    loss.identity().instanceId().value(),
                    loss.identity().definitionId().value());
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    private static boolean markReviewRequired(
            Connection connection,
            UUID operationId,
            UUID instanceId,
            UUID definitionId,
            String reason,
            long reviewedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE destructive_targets SET state = 'REVIEW_REQUIRED', "
                        + "effect_state = 'AMBIGUOUS', claim_token = NULL, "
                        + "claim_expires_at = NULL, before_fingerprint = NULL, "
                        + "after_fingerprint = NULL, last_error = ?, updated_at = ? "
                        + "WHERE operation_id = ? AND instance_id = ? "
                        + "AND definition_id = ? AND state = 'PENDING'")) {
            statement.setString(1, boundedReason(reason));
            statement.setLong(2, reviewedAt);
            bindIdentity(statement, 3, operationId, instanceId, definitionId);
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    private static void bindIdentity(
            PreparedStatement statement,
            int firstIndex,
            UUID operationId,
            UUID instanceId,
            UUID definitionId) throws SQLException {
        statement.setString(firstIndex, operationId.toString());
        statement.setString(firstIndex + 1, instanceId.toString());
        statement.setString(firstIndex + 2, definitionId.toString());
    }

    private static void appendAudit(
            Connection connection,
            UUID operationId,
            UUID instanceId,
            String effectState,
            long occurredAt) throws SQLException {
        SQLiteDestructiveControlStore.appendAudit(
                connection,
                operationId,
                "AMBIGUOUS".equals(effectState)
                        ? "destructive_target_review_required"
                        : "destructive_target_satisfied_by_void_loss",
                "SYSTEM",
                detail(instanceId, effectState),
                occurredAt);
    }

    private static String boundedReason(String reason) {
        String normalized = reason.strip();
        return normalized.length() <= MAX_ERROR_LENGTH
                ? normalized
                : normalized.substring(0, MAX_ERROR_LENGTH);
    }

    private static String detail(UUID instanceId, String effectState) {
        return "{\"instanceId\":\"" + instanceId
                + "\",\"effectState\":\"" + effectState
                + "\",\"source\":\"TERMINAL_VOID\"}";
    }

    private record PendingTarget(
            UUID operationId,
            UUID instanceId,
            UUID definitionId) {
    }
}
