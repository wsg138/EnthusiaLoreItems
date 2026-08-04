package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationRunnerTest {
    private static final int EXPECTED_SCHEMA_VERSION_COUNT = 2;
    private static final String DEFINITION_ID = "10000000-0000-0000-0000-000000000001";
    private static final String INSTANCE_ID = "20000000-0000-0000-0000-000000000001";
    private static final String PENDING_MUTATION_ID =
            "30000000-0000-0000-0000-000000000001";
    private static final String COMPLETED_MUTATION_ID =
            "30000000-0000-0000-0000-000000000002";

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsTheCurrentSchemaAndCanRunAgain() throws SQLException {
        SQLiteConnectionFactory factory =
                new SQLiteConnectionFactory(temporaryDirectory.resolve("loreitems.db"), 5_000);
        MigrationRunner runner = new MigrationRunner();

        try (Connection connection = factory.open()) {
            runner.migrate(connection);
            runner.migrate(connection);

            assertEquals(EXPECTED_SCHEMA_VERSION_COUNT, countSchemaHistory(connection));
            assertEquals(1, countTable(connection, "direct_deliveries"));
            assertEquals(1, countIndex(connection, "uq_template_update_instance_revision"));
        }
    }

    @Test
    void upgradesV1AndConsolidatesDuplicateTemplateUpdates() throws SQLException {
        SQLiteConnectionFactory factory =
                new SQLiteConnectionFactory(temporaryDirectory.resolve("upgrade.db"), 5_000);
        MigrationRunner runner = new MigrationRunner();

        try (Connection connection = factory.open()) {
            runner.migrate(connection);
            removeVersionTwo(connection);
            insertDefinitionGraph(connection);
            insertTemplateUpdate(connection, PENDING_MUTATION_ID, "PENDING", 2_000L);
            insertTemplateUpdate(connection, COMPLETED_MUTATION_ID, "COMPLETED", 1_000L);

            runner.migrate(connection);

            assertEquals(EXPECTED_SCHEMA_VERSION_COUNT, countSchemaHistory(connection));
            assertEquals(1, countIndex(connection, "idx_instances_revision_rollout"));
            assertEquals(1, countIndex(connection, "uq_template_update_instance_revision"));
            assertEquals(1, countIndex(connection, "idx_template_update_mutations"));
            assertEquals(1, countTemplateUpdates(connection));
            assertEquals(COMPLETED_MUTATION_ID, findTemplateUpdateMutationId(connection));
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

    private static void removeVersionTwo(Connection connection) throws SQLException {
        try (PreparedStatement removeHistory = connection.prepareStatement(
                "DELETE FROM schema_history WHERE version = 2")) {
            removeHistory.executeUpdate();
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP INDEX idx_instances_revision_rollout");
            statement.executeUpdate("DROP INDEX uq_template_update_instance_revision");
            statement.executeUpdate("DROP INDEX idx_template_update_mutations");
        }
    }

    private static void insertDefinitionGraph(Connection connection) throws SQLException {
        try (PreparedStatement insertDefinition = connection.prepareStatement(
                "INSERT INTO lore_definitions("
                        + "definition_id, lookup_key, display_name, current_revision, created_at"
                        + ") VALUES (?, ?, ?, ?, ?)")) {
            insertDefinition.setString(1, DEFINITION_ID);
            insertDefinition.setString(2, "migration-test");
            insertDefinition.setString(3, "Migration Test");
            insertDefinition.setLong(4, 1L);
            insertDefinition.setLong(5, 1L);
            insertDefinition.executeUpdate();
        }
        try (PreparedStatement insertRevision = connection.prepareStatement(
                "INSERT INTO lore_definition_revisions("
                        + "definition_id, revision, codec_version, template_blob, created_at"
                        + ") VALUES (?, ?, ?, ?, ?)")) {
            insertRevision.setString(1, DEFINITION_ID);
            insertRevision.setLong(2, 1L);
            insertRevision.setInt(3, 1);
            insertRevision.setBytes(4, new byte[] {1});
            insertRevision.setLong(5, 1L);
            insertRevision.executeUpdate();
        }
        try (PreparedStatement insertInstance = connection.prepareStatement(
                "INSERT INTO lore_instances("
                        + "instance_id, definition_id, applied_revision, desired_revision, "
                        + "lifecycle_state, created_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?)")) {
            insertInstance.setString(1, INSTANCE_ID);
            insertInstance.setString(2, DEFINITION_ID);
            insertInstance.setLong(3, 1L);
            insertInstance.setLong(4, 1L);
            insertInstance.setString(5, "ACTIVE");
            insertInstance.setLong(6, 1L);
            insertInstance.executeUpdate();
        }
    }

    private static void insertTemplateUpdate(
            Connection connection, String mutationId, String state, long updatedAt)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO pending_mutations("
                        + "mutation_id, mutation_type, definition_id, instance_id, "
                        + "desired_revision, state, attempt_count, created_at, updated_at"
                        + ") VALUES (?, 'TEMPLATE_UPDATE', ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, mutationId);
            insert.setString(2, DEFINITION_ID);
            insert.setString(3, INSTANCE_ID);
            insert.setLong(4, 1L);
            insert.setString(5, state);
            insert.setInt(6, 0);
            insert.setLong(7, 1_000L);
            insert.setLong(8, updatedAt);
            insert.executeUpdate();
        }
    }

    private static int countTemplateUpdates(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                     "SELECT COUNT(*) FROM pending_mutations "
                             + "WHERE mutation_type = 'TEMPLATE_UPDATE' "
                             + "AND instance_id = ? AND desired_revision = 1")) {
            query.setString(1, INSTANCE_ID);
            try (ResultSet resultSet = query.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static String findTemplateUpdateMutationId(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                     "SELECT mutation_id FROM pending_mutations "
                             + "WHERE mutation_type = 'TEMPLATE_UPDATE' "
                             + "AND instance_id = ? AND desired_revision = 1")) {
            query.setString(1, INSTANCE_ID);
            try (ResultSet resultSet = query.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
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

    private static int countTable(Connection connection, String tableName) throws SQLException {
        return countSchemaObject(connection, "table", tableName);
    }

    private static int countIndex(Connection connection, String indexName) throws SQLException {
        return countSchemaObject(connection, "index", indexName);
    }

    private static int countSchemaObject(
            Connection connection, String type, String name) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                     "SELECT COUNT(*) FROM sqlite_master WHERE type = ? AND name = ?")) {
            query.setString(1, type);
            query.setString(2, name);
            try (ResultSet resultSet = query.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
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
