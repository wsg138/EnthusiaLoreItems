package net.enthusia.loreitems.sqlite;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.OperationView;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.TargetView;
import net.enthusia.loreitems.domain.DestructiveEffectState;
import net.enthusia.loreitems.domain.DestructiveOperationState;
import net.enthusia.loreitems.domain.DestructiveOperationType;
import net.enthusia.loreitems.domain.DestructiveTargetState;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;

final class SQLiteDestructiveRows {
    private SQLiteDestructiveRows() {
    }

    static OperationView readOperation(ResultSet resultSet) throws SQLException {
        String exactInstance = resultSet.getString("exact_instance_id");
        long terminalValue = resultSet.getLong("terminal_at");
        Long terminalAt = resultSet.wasNull() ? null : terminalValue;
        return new OperationView(
                UUID.fromString(resultSet.getString("operation_id")),
                DestructiveOperationType.valueOf(resultSet.getString("operation_type")),
                definitionId(resultSet.getString("definition_id")),
                exactInstance == null ? null : instanceId(exactInstance),
                new TemplateRevision(resultSet.getLong("expected_revision")),
                DestructiveOperationState.valueOf(resultSet.getString("state")),
                resultSet.getString("actor_id"),
                resultSet.getString("idempotency_key"),
                resultSet.getLong("target_count"),
                count(resultSet, "pending_count"),
                count(resultSet, "claimed_count"),
                count(resultSet, "review_count"),
                count(resultSet, "completed_count"),
                count(resultSet, "aborted_count"),
                resultSet.getLong("accepted_at"),
                resultSet.getLong("updated_at"),
                terminalAt);
    }

    static TargetView readTarget(ResultSet resultSet) throws SQLException {
        long claimExpiryValue = resultSet.getLong("claim_expires_at");
        Long claimExpiry = resultSet.wasNull() ? null : claimExpiryValue;
        return new TargetView(
                UUID.fromString(resultSet.getString("operation_id")),
                instanceId(resultSet.getString("instance_id")),
                definitionId(resultSet.getString("definition_id")),
                new TemplateRevision(resultSet.getLong("expected_applied_revision")),
                resultSet.getString("expected_location_type"),
                resultSet.getString("expected_location_key"),
                resultSet.getString("expected_container_path"),
                DestructiveTargetState.valueOf(resultSet.getString("state")),
                DestructiveEffectState.valueOf(resultSet.getString("effect_state")),
                resultSet.getInt("attempt_count"),
                claimExpiry,
                resultSet.getString("before_fingerprint"),
                resultSet.getString("after_fingerprint"),
                resultSet.getString("last_error"),
                resultSet.getLong("created_at"),
                resultSet.getLong("updated_at"));
    }

    static void setNullableString(
            PreparedStatement statement,
            int index,
            String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    static LoreDefinitionId definitionId(String value) {
        return new LoreDefinitionId(UUID.fromString(value));
    }

    static LoreInstanceId instanceId(String value) {
        return new LoreInstanceId(UUID.fromString(value));
    }

    private static long count(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? 0L : value;
    }
}