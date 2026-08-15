package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerInventoryLocationMigrationTest {
    private static final int PRE_CANONICAL_LOCATION_VERSION = 8;
    private static final String DEFINITION = "10000000-0000-0000-0000-000000000001";
    private static final String EXISTING_INSTANCE = "20000000-0000-0000-0000-000000000001";
    private static final String NEW_INSTANCE = "20000000-0000-0000-0000-000000000002";
    private static final String PLAYER = "11111111-1111-1111-1111-111111111111";

    @TempDir
    Path temporaryDirectory;

    @Test
    void v9RepairsExistingRowsAndCanonicalizesFutureWrites() throws SQLException {
        SQLiteConnectionFactory factory = new SQLiteConnectionFactory(
                temporaryDirectory.resolve("player-location-v9.db"), 5_000);
        MigrationRunner runner = new MigrationRunner();
        try (Connection connection = factory.open()) {
            runner.migrateThrough(connection, PRE_CANONICAL_LOCATION_VERSION);
            seedDefinition(connection);
            insertInstance(connection, EXISTING_INSTANCE);
            long existingObservation = insertPlayerObservation(
                    connection, EXISTING_INSTANCE, PLAYER, "slot:2", "direct-delivery-completed");
            insertCurrentState(connection, EXISTING_INSTANCE, PLAYER, "slot:2", existingObservation);

            runner.migrate(connection);

            assertCanonical(connection, EXISTING_INSTANCE);

            insertInstance(connection, NEW_INSTANCE);
            long newObservation = insertPlayerObservation(
                    connection, NEW_INSTANCE, PLAYER, "slot:3", "campaign-delivery-completed");
            insertCurrentState(connection, NEW_INSTANCE, PLAYER, "slot:3", newObservation);

            assertCanonical(connection, NEW_INSTANCE);
        }
    }

    private static void seedDefinition(Connection connection) throws SQLException {
        execute(connection,
                "INSERT INTO lore_definitions(definition_id, lookup_key, display_name, "
                        + "current_revision, created_at) VALUES ('" + DEFINITION
                        + "', 'player-location-test', 'Player Location Test', 1, 1)");
        execute(connection,
                "INSERT INTO lore_definition_revisions(definition_id, revision, codec_version, "
                        + "template_blob, created_at) VALUES ('" + DEFINITION
                        + "', 1, 1, X'01', 1)");
    }

    private static void insertInstance(Connection connection, String instanceId) throws SQLException {
        execute(connection,
                "INSERT INTO lore_instances(instance_id, definition_id, applied_revision, "
                        + "desired_revision, lifecycle_state, created_at) VALUES ('" + instanceId
                        + "', '" + DEFINITION + "', 1, 1, 'ACTIVE', 1)");
    }

    private static long insertPlayerObservation(
            Connection connection,
            String instanceId,
            String locationKey,
            String containerPath,
            String source) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO instance_observations(instance_id, definition_id, location_type, "
                        + "location_key, container_path, confidence, source, observed_at) "
                        + "VALUES (?, ?, 'PLAYER_INVENTORY', ?, ?, 'CONFIRMED_NOW', ?, 2)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, instanceId);
            statement.setString(2, DEFINITION);
            statement.setString(3, locationKey);
            statement.setString(4, containerPath);
            statement.setString(5, source);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private static void insertCurrentState(
            Connection connection,
            String instanceId,
            String locationKey,
            String containerPath,
            long observationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO instance_current_state(instance_id, state, location_type, location_key, "
                        + "container_path, last_observation_id, state_revision, updated_at) "
                        + "VALUES (?, 'CONFIRMED_NOW', 'PLAYER_INVENTORY', ?, ?, ?, 1, 2)")) {
            statement.setString(1, instanceId);
            statement.setString(2, locationKey);
            statement.setString(3, containerPath);
            statement.setLong(4, observationId);
            statement.executeUpdate();
        }
    }

    private static void assertCanonical(Connection connection, String instanceId) throws SQLException {
        assertEquals("player:" + PLAYER, scalarText(
                connection,
                "SELECT location_key FROM instance_observations WHERE instance_id = '" + instanceId + "'"));
        assertEquals("player:" + PLAYER, scalarText(
                connection,
                "SELECT location_key FROM instance_current_state WHERE instance_id = '" + instanceId + "'"));
    }

    private static String scalarText(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) { // nosemgrep
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql); // nosemgrep
        }
    }
}
