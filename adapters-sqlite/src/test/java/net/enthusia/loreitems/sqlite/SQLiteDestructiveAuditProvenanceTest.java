package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteDestructiveAuditProvenanceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void automaticDestructiveAuditIsPersistedAsSystemActivity() throws Exception {
        try (Connection connection = migratedConnection("system-audit.db")) {
            UUID operationId = UUID.randomUUID();

            SQLiteDestructiveControlStore.appendAudit(
                    connection,
                    operationId,
                    "destructive_target_removed",
                    "SYSTEM",
                    "{}",
                    2_000L);

            assertAuditActor(connection, operationId, "system", "SYSTEM");
        }
    }

    @Test
    void staffDestructiveAuditRemainsStaffActivity() throws Exception {
        try (Connection connection = migratedConnection("staff-audit.db")) {
            UUID operationId = UUID.randomUUID();

            SQLiteDestructiveControlStore.appendAudit(
                    connection,
                    operationId,
                    "destructive_operation_paused",
                    "admin-user",
                    "{}",
                    2_000L);

            assertAuditActor(connection, operationId, "STAFF", "admin-user");
        }
    }

    private Connection migratedConnection(String fileName) throws Exception {
        Connection connection = new SQLiteConnectionFactory(
                temporaryDirectory.resolve(fileName), 5_000).open();
        new MigrationRunner().migrate(connection);
        return connection;
    }

    private static void assertAuditActor(
            Connection connection,
            UUID operationId,
            String expectedActorType,
            String expectedActorId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT actor_type, actor_id FROM audit_events "
                        + "WHERE aggregate_type = 'destructive_operation' AND aggregate_id = ?")) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                assertEquals(expectedActorType, resultSet.getString("actor_type"));
                assertEquals(expectedActorId, resultSet.getString("actor_id"));
            }
        }
    }
}
