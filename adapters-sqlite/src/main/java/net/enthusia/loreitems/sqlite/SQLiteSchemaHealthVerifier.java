package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

final class SQLiteSchemaHealthVerifier {
    private static final String SQLITE_OK = "ok";
    private static final List<SchemaObject> REQUIRED_OBJECTS = List.of(
            table("schema_history"),
            table("lore_definitions"),
            table("lore_definition_revisions"),
            table("lore_instances"),
            table("instance_observations"),
            table("instance_current_state"),
            table("instance_anomalies"),
            table("pending_mutations"),
            table("direct_deliveries"),
            table("distribution_campaigns"),
            table("distribution_recipients"),
            table("external_delivery_requests"),
            table("deleted_definition_markers"),
            table("audit_events"),
            table("template_edit_confirmations"),
            table("destructive_operations"),
            table("destructive_targets"),
            table("distribution_campaign_revision_snapshots"),
            index("uq_active_definition_lookup_key"),
            index("uq_anomalies_active_identity"),
            index("uq_template_update_instance_revision"),
            index("uq_distribution_recipient_player"),
            index("uq_distribution_recipient_instance"),
            index("uq_destructive_target_active_instance"),
            trigger("distribution_campaign_identity_is_immutable"),
            trigger("distribution_recipient_snapshot_is_immutable"),
            trigger("distribution_recipient_requires_draft_campaign"),
            trigger("deleted_definition_marker_is_immutable"),
            trigger("deleted_definition_marker_cannot_be_deleted"),
            trigger("destructive_operation_identity_is_immutable"),
            trigger("destructive_target_identity_is_immutable"),
            trigger("distribution_campaign_revision_is_immutable"),
            trigger("distribution_campaign_revision_cannot_be_deleted"),
            trigger("canonicalize_player_inventory_observation_insert"),
            trigger("canonicalize_player_inventory_current_insert"),
            trigger("canonicalize_player_inventory_current_update"));

    private SQLiteSchemaHealthVerifier() {
    }

    static void verify(Connection connection) throws SQLException {
        verifyQuickCheck(connection);
        verifyForeignKeys(connection);
        verifyRequiredObjects(connection);
    }

    private static void verifyQuickCheck(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("PRAGMA quick_check(1)")) {
            if (!resultSet.next() || !SQLITE_OK.equalsIgnoreCase(resultSet.getString(1))) {
                throw new SQLException("SQLite quick_check reported database corruption");
            }
        }
    }

    private static void verifyForeignKeys(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (resultSet.next()) {
                throw new SQLException(
                        "SQLite foreign_key_check reported invalid durable references in table "
                                + resultSet.getString("table"));
            }
        }
    }

    private static void verifyRequiredObjects(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = ? AND name = ?")) {
            for (SchemaObject object : REQUIRED_OBJECTS) {
                statement.setString(1, object.type());
                statement.setString(2, object.name());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new SQLException(
                                "SQLite schema is missing required " + object.type()
                                        + " " + object.name());
                    }
                }
            }
        }
    }

    private static SchemaObject table(String name) {
        return new SchemaObject("table", name);
    }

    private static SchemaObject index(String name) {
        return new SchemaObject("index", name);
    }

    private static SchemaObject trigger(String name) {
        return new SchemaObject("trigger", name);
    }

    private record SchemaObject(String type, String name) {}
}
