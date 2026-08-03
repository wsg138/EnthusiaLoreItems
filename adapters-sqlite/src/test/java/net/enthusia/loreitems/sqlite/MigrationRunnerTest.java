package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationRunnerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsTheFoundationSchemaAndCanRunAgain() throws SQLException {
        SQLiteConnectionFactory factory =
                new SQLiteConnectionFactory(temporaryDirectory.resolve("loreitems.db"), 5_000);
        MigrationRunner runner = new MigrationRunner();

        try (Connection connection = factory.open()) {
            runner.migrate(connection);
            runner.migrate(connection);

            assertEquals(1, countSchemaHistory(connection));
            assertEquals(
                    1,
                    countDirectDeliveryTables(connection));
        }
    }

    @Test
    void enforcesExternalIdempotencyKeys() throws SQLException {
        SQLiteConnectionFactory factory =
                new SQLiteConnectionFactory(temporaryDirectory.resolve("idempotency.db"), 5_000);
        MigrationRunner runner = new MigrationRunner();

        try (Connection connection = factory.open()) {
            runner.migrate(connection);
            insertExternalRequest(connection, "reward-claim-42");
            assertThrows(
                    SQLException.class,
                    () -> insertExternalRequest(connection, "reward-claim-42"));
        }
    }

    private static int countSchemaHistory(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                     "SELECT COUNT(*) FROM schema_history");
             ResultSet resultSet = query.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static int countDirectDeliveryTables(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                     "SELECT COUNT(*) FROM sqlite_master "
                             + "WHERE type = 'table' AND name = 'direct_deliveries'");
             ResultSet resultSet = query.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static void insertExternalRequest(Connection connection, String operationId)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO external_delivery_requests("
                        + "external_operation_id, definition_key, player_id, outcome, created_at, updated_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, operationId);
            insert.setString(2, "example");
            insert.setString(3, "00000000-0000-0000-0000-000000000001");
            insert.setString(4, "ACCEPTED_QUEUED");
            insert.setLong(5, 1L);
            insert.setLong(6, 1L);
            insert.executeUpdate();
        }
    }
}
