package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import net.enthusia.loreitems.application.PreparedVoidLoss;

final class SQLiteVoidDestructiveReconciliation {
    private static final int SINGLE_ROW = 1;

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
        SQLiteDestructiveControlStore.appendAudit(
                connection,
                operationId,
                "destructive_target_satisfied_by_void_loss",
                "SYSTEM",
                detail(loss),
                completedAt);
        SQLiteDestructiveControlStore.refreshParentTerminalState(
                connection, operationId, completedAt);
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
            statement.setString(2, operationId.toString());
            statement.setString(3, loss.identity().instanceId().value().toString());
            statement.setString(4, loss.identity().definitionId().value().toString());
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    private static String detail(PreparedVoidLoss loss) {
        return "{\"instanceId\":\"" + loss.identity().instanceId().value()
                + "\",\"effect\":\"TERMINAL_VOID\"}";
    }
}
