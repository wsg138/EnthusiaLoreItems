package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationUpgradeMatrixTest {
    private static final int LATEST_VERSION = 7;
    private static final String ACTIVE_DEFINITION = "10000000-0000-0000-0000-000000000001";
    private static final String DELETED_DEFINITION = "10000000-0000-0000-0000-000000000002";
    private static final String INSTANCE = "20000000-0000-0000-0000-000000000001";
    private static final String MUTATION = "30000000-0000-0000-0000-000000000001";
    private static final String CAMPAIGN = "40000000-0000-0000-0000-000000000001";

    @TempDir
    Path temporaryDirectory;

    @Test
    void everyCommittedSchemaVersionUpgradesWithDurableStateAndIntegrityPreserved()
            throws SQLException {
        for (int version = 1; version <= LATEST_VERSION; version++) {
            verifyUpgradeFrom(version);
        }
    }

    @Test
    void interruptedMigrationRollsBackPartialSchemaAndCanBeRetried() throws SQLException {
        Path database = temporaryDirectory.resolve("interrupted-v7.db");
        SQLiteConnectionFactory factory = new SQLiteConnectionFactory(database, 5_000);
        MigrationRunner runner = new MigrationRunner();

        try (Connection connection = factory.open()) {
            runner.migrateThrough(connection, 6);
            seedHistoricalState(connection, 6);
            execute(connection,
                    "CREATE INDEX idx_distribution_campaign_revision "
                            + "ON distribution_campaigns(campaign_id)");

            assertThrows(SQLException.class, () -> runner.migrate(connection));
            assertEquals(6, scalarInt(connection, "SELECT COUNT(*) FROM schema_history"));
            assertFalse(schemaObjectExists(connection, "table", "distribution_campaign_revision_snapshots"));
            assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM distribution_campaigns"));

            execute(connection, "DROP INDEX idx_distribution_campaign_revision");
            runner.migrate(connection);
            assertCurrentSchema(connection);
            assertEquals(1, scalarInt(connection,
                    "SELECT COUNT(*) FROM distribution_campaign_revision_snapshots"));
        }
    }

    private void verifyUpgradeFrom(int version) throws SQLException {
        Path database = temporaryDirectory.resolve("upgrade-v" + version + ".db");
        SQLiteConnectionFactory factory = new SQLiteConnectionFactory(database, 5_000);
        MigrationRunner runner = new MigrationRunner();
        try (Connection connection = factory.open()) {
            runner.migrateThrough(connection, version);
            seedHistoricalState(connection, version);
        }
        try (Connection connection = factory.open()) {
            runner.migrate(connection);
            assertCurrentSchema(connection);
            assertHistoricalStatePreserved(connection);
        }
    }

    private static void seedHistoricalState(Connection connection, int version) throws SQLException {
        seedDefinitions(connection);
        seedTrackedInstance(connection);
        seedPendingWork(connection);
        seedDeletedMarker(connection);
        seedCampaign(connection, version);
        seedAudit(connection);
    }

    private static void seedDefinitions(Connection connection) throws SQLException {
        execute(connection,
                "INSERT INTO lore_definitions(definition_id, lookup_key, display_name, "
                        + "current_revision, created_at) VALUES ('" + ACTIVE_DEFINITION
                        + "', 'upgrade-active', 'Upgrade Active', 1, 1)");
        execute(connection,
                "INSERT INTO lore_definition_revisions(definition_id, revision, codec_version, "
                        + "template_blob, created_at) VALUES ('" + ACTIVE_DEFINITION
                        + "', 1, 1, X'01', 1)");
        execute(connection,
                "INSERT INTO lore_definitions(definition_id, lookup_key, display_name, "
                        + "current_revision, created_at, deleted_at) VALUES ('" + DELETED_DEFINITION
                        + "', 'upgrade-deleted', 'Upgrade Deleted', 1, 1, 2)");
        execute(connection,
                "INSERT INTO lore_definition_revisions(definition_id, revision, codec_version, "
                        + "template_blob, created_at) VALUES ('" + DELETED_DEFINITION
                        + "', 1, 1, X'02', 1)");
    }

    private static void seedTrackedInstance(Connection connection) throws SQLException {
        execute(connection,
                "INSERT INTO lore_instances(instance_id, definition_id, applied_revision, "
                        + "desired_revision, lifecycle_state, created_at) VALUES ('" + INSTANCE
                        + "', '" + ACTIVE_DEFINITION + "', 1, 1, 'ACTIVE', 1)");
        execute(connection,
                "INSERT INTO instance_observations(instance_id, definition_id, location_type, "
                        + "location_key, confidence, source, observed_at) VALUES ('" + INSTANCE
                        + "', '" + ACTIVE_DEFINITION
                        + "', 'PLAYER_INVENTORY', 'player:test', 'CONFIRMED_NOW', 'upgrade', 2)");
        execute(connection,
                "INSERT INTO instance_current_state(instance_id, state, location_type, "
                        + "location_key, last_observation_id, state_revision, updated_at) VALUES ('"
                        + INSTANCE
                        + "', 'CONFIRMED_NOW', 'PLAYER_INVENTORY', 'player:test', 1, 1, 2)");
    }

    private static void seedPendingWork(Connection connection) throws SQLException {
        execute(connection,
                "INSERT INTO pending_mutations(mutation_id, mutation_type, definition_id, "
                        + "instance_id, desired_revision, state, attempt_count, created_at, updated_at) "
                        + "VALUES ('" + MUTATION + "', 'TEMPLATE_UPDATE', '" + ACTIVE_DEFINITION
                        + "', '" + INSTANCE + "', 1, 'PENDING', 0, 3, 3)");
    }

    private static void seedDeletedMarker(Connection connection) throws SQLException {
        execute(connection,
                "INSERT INTO deleted_definition_markers(definition_id, lookup_key, deleted_at) "
                        + "VALUES ('" + DELETED_DEFINITION + "', 'upgrade-deleted', 2)");
    }

    private static void seedCampaign(Connection connection, int version) throws SQLException {
        execute(connection,
                "INSERT INTO distribution_campaigns(campaign_id, source_fingerprint, source_name, "
                        + "display_name, definition_id, state, created_at, updated_at) VALUES ('"
                        + CAMPAIGN + "', 'upgrade-source', 'upgrade-source.txt', 'Upgrade Campaign', '"
                        + ACTIVE_DEFINITION + "', 'DRAFT', 4, 4)");
        String recipientState = version < 6 ? "PENDING_NAME" : "UNRESOLVED";
        execute(connection,
                "INSERT INTO distribution_recipients(campaign_id, recipient_key, snapshot_index, "
                        + "original_value, state, attempt_count, updated_at) VALUES ('" + CAMPAIGN
                        + "', 'name:upgrade-user', 0, 'UpgradeUser', '" + recipientState + "', 0, 4)");
        if (version >= 7) {
            execute(connection,
                    "INSERT INTO distribution_campaign_revision_snapshots(campaign_id, definition_id, "
                            + "definition_revision, created_at) VALUES ('" + CAMPAIGN + "', '"
                            + ACTIVE_DEFINITION + "', 1, 4)");
        }
    }

    private static void seedAudit(Connection connection) throws SQLException {
        execute(connection,
                "INSERT INTO audit_events(aggregate_type, aggregate_id, event_type, actor_type, "
                        + "detail_json, occurred_at) VALUES ('INSTANCE', '" + INSTANCE
                        + "', 'UPGRADE_FIXTURE', 'SYSTEM', '{}', 5)");
    }

    private static void assertHistoricalStatePreserved(Connection connection) throws SQLException {
        assertEquals(2, scalarInt(connection, "SELECT COUNT(*) FROM lore_definitions"));
        assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM lore_instances"));
        assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM instance_observations"));
        assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM instance_current_state"));
        assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM pending_mutations"));
        assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM deleted_definition_markers"));
        assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM audit_events"));
        assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM distribution_campaigns"));
        assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM distribution_recipients"));
        assertEquals("UNRESOLVED", scalarText(connection,
                "SELECT state FROM distribution_recipients WHERE campaign_id = '" + CAMPAIGN + "'"));
        assertEquals(1, scalarInt(connection,
                "SELECT COUNT(*) FROM distribution_campaign_revision_snapshots"));
    }

    private static void assertCurrentSchema(Connection connection) throws SQLException {
        assertEquals(LATEST_VERSION, scalarInt(connection, "SELECT COUNT(*) FROM schema_history"));
        assertEquals("ok", scalarText(connection, "PRAGMA integrity_check"));
        assertFalse(hasRows(connection, "PRAGMA foreign_key_check"));
        assertEquals("wal", scalarText(connection, "PRAGMA journal_mode"));
        assertEquals(5_000, scalarInt(connection, "PRAGMA busy_timeout"));
        assertTrue(schemaObjectExists(connection, "index", "uq_template_update_instance_revision"));
        assertTrue(schemaObjectExists(connection, "index", "idx_mutations_type_claimable"));
        assertTrue(schemaObjectExists(connection, "index", "idx_mutations_type_review"));
        assertTrue(schemaObjectExists(connection, "index", "uq_destructive_target_active_instance"));
        assertTrue(schemaObjectExists(connection, "index", "idx_distribution_campaign_revision"));
    }

    private static boolean schemaObjectExists(Connection connection, String type, String name)
            throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = ? AND name = ?")) {
            query.setString(1, type);
            query.setString(2, name);
            try (ResultSet resultSet = query.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static boolean hasRows(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) { // nosemgrep
            return resultSet.next();
        }
    }

    private static int scalarInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) { // nosemgrep
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
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
            // SQL is assembled only from fixed test constants and closed version-derived states.
            statement.executeUpdate(sql); // nosemgrep
        }
    }
}
