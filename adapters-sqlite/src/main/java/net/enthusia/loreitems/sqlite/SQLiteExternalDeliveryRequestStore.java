package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import net.enthusia.loreitems.application.ExternalDeliveryCommand;
import net.enthusia.loreitems.application.ExternalDeliveryOutcome;

/** Persists and replays the durable external-operation idempotency fence. */
final class SQLiteExternalDeliveryRequestStore {
    private static final int SINGLE_UPDATED_ROW = 1;

    private SQLiteExternalDeliveryRequestStore() {}

    static ExistingRequest find(Connection connection, String operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT definition_key, player_id, delivery_id, outcome "
                        + "FROM external_delivery_requests WHERE external_operation_id = ?")) {
            statement.setString(1, operationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                String deliveryValue = resultSet.getString("delivery_id");
                return new ExistingRequest(
                        resultSet.getString("definition_key"),
                        UUID.fromString(resultSet.getString("player_id")),
                        deliveryValue == null ? null : UUID.fromString(deliveryValue),
                        resultSet.getString("outcome"));
            }
        }
    }

    static void insert(
            Connection connection,
            ExternalDeliveryCommand command,
            UUID deliveryId,
            ExternalDeliveryOutcome outcome,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO external_delivery_requests(external_operation_id, definition_key, "
                        + "player_id, delivery_id, outcome, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, command.externalOperationId());
            statement.setString(2, command.definitionKey().value());
            statement.setString(3, command.playerId().toString());
            statement.setString(4, deliveryId == null ? null : deliveryId.toString());
            statement.setString(5, outcome.name());
            statement.setLong(6, now);
            statement.setLong(7, now);
            statement.executeUpdate();
        }
    }

    static void replaceUnknown(
            Connection connection,
            ExternalDeliveryCommand command,
            UUID deliveryId,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE external_delivery_requests SET delivery_id = ?, outcome = ?, updated_at = ? "
                        + "WHERE external_operation_id = ? AND definition_key = ? AND player_id = ? "
                        + "AND outcome = ? AND delivery_id IS NULL")) {
            statement.setString(1, deliveryId.toString());
            statement.setString(2, ExternalDeliveryOutcome.ACCEPTED_QUEUED.name());
            statement.setLong(3, now);
            statement.setString(4, command.externalOperationId());
            statement.setString(5, command.definitionKey().value());
            statement.setString(6, command.playerId().toString());
            statement.setString(7, ExternalDeliveryOutcome.UNKNOWN_DEFINITION.name());
            if (statement.executeUpdate() != SINGLE_UPDATED_ROW) {
                throw new SQLException(
                        "External delivery retry lost its durable unknown-definition fence");
            }
        }
    }

    record ExistingRequest(
            String definitionKey,
            UUID playerId,
            UUID deliveryId,
            String outcome) {}
}
