package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
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
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                        SELECT aggregate_id, actor_type, actor_id
                        FROM audit_events
                        WHERE aggregate_type = 'destructive_operation'
                        """)) {
            while (resultSet.next()) {
                if (!operationId.toString().equals(resultSet.getString("aggregate_id"))) {
                    continue;
                }
                assertEquals(expectedActorType, resultSet.getString("actor_type"));
                assertEquals(expectedActorId, resultSet.getString("actor_id"));
                return;
            }
        }
        throw new AssertionError("Missing expected destructive-operation audit event");
    }
}
