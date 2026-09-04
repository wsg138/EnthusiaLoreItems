package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteSchemaHealthVerifierTest {
    private static final String ORPHAN_INSTANCE_ID =
            "10000000-0000-0000-0000-000000000001";
    private static final String MISSING_DEFINITION_ID =
            "20000000-0000-0000-0000-000000000001";

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsCurrentHistoryWhenRequiredTableIsMissing() throws SQLException {
        try (Connection connection = migratedConnection("missing-table.db")) {
            execute(connection, "DROP TABLE direct_deliveries");

            SQLException failure = assertThrows(
                    SQLException.class, () -> new MigrationRunner().migrate(connection));

            assertTrue(failure.getMessage().contains("required table direct_deliveries"));
        }
    }

    @Test
    void rejectsCurrentHistoryWhenCorrectnessIndexIsMissing() throws SQLException {
        try (Connection connection = migratedConnection("missing-index.db")) {
            execute(connection, "DROP INDEX uq_template_update_instance_revision");

            SQLException failure = assertThrows(
                    SQLException.class, () -> new MigrationRunner().migrate(connection));

            assertTrue(failure.getMessage()
                    .contains("required index uq_template_update_instance_revision"));
        }
    }

    @Test
    void rejectsCurrentHistoryWhenCorrectnessIndexDefinitionIsWeakened() throws SQLException {
        try (Connection connection = migratedConnection("weakened-index.db")) {
            execute(connection, "DROP INDEX uq_distribution_recipient_instance");
            execute(connection,
                    "CREATE INDEX uq_distribution_recipient_instance "
                            + "ON distribution_recipients(instance_id) WHERE instance_id IS NOT NULL");

            SQLException failure = assertThrows(
                    SQLException.class, () -> new MigrationRunner().migrate(connection));

            assertTrue(failure.getMessage()
                    .contains("invalid required index uq_distribution_recipient_instance"));
        }
    }

    @Test
    void rejectsCurrentHistoryWhenInvariantTriggerIsMissing() throws SQLException {
        try (Connection connection = migratedConnection("missing-trigger.db")) {
            execute(connection, "DROP TRIGGER destructive_target_identity_is_immutable");

            SQLException failure = assertThrows(
                    SQLException.class, () -> new MigrationRunner().migrate(connection));

            assertTrue(failure.getMessage()
                    .contains("required trigger destructive_target_identity_is_immutable"));
        }
    }

    @Test
    void rejectsForeignKeyViolationThatWasWrittenWithChecksDisabled() throws SQLException {
        try (Connection connection = migratedConnection("foreign-key-violation.db")) {
            execute(connection, "PRAGMA foreign_keys = OFF");
            insertOrphanInstance(connection);
            execute(connection, "PRAGMA foreign_keys = ON");

            SQLException failure = assertThrows(
                    SQLException.class, () -> new MigrationRunner().migrate(connection));

            assertTrue(failure.getMessage().contains("foreign_key_check"));
        }
    }

    private Connection migratedConnection(String fileName) throws SQLException {
        SQLiteConnectionFactory factory = new SQLiteConnectionFactory(
                temporaryDirectory.resolve(fileName), 5_000);
        Connection connection = factory.open();
        new MigrationRunner().migrate(connection);
        return connection;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql); // nosemgrep
        }
    }

    private static void insertOrphanInstance(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO lore_instances(instance_id, definition_id, applied_revision, "
                        + "desired_revision, lifecycle_state, created_at, terminal_at) "
                        + "VALUES (?, ?, 1, 1, 'ACTIVE', 1, NULL)")) {
            statement.setString(1, ORPHAN_INSTANCE_ID);
            statement.setString(2, MISSING_DEFINITION_ID);
            statement.executeUpdate();
        }
    }
}
