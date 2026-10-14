package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Synthetic offline SQLite rows: malformed v2 strings must never look migrated. */
class SQLiteLegacyKeyFormatParityTest {
    private static final String WORLD = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String ENTITY = "11111111-2222-3333-4444-555555555555";

    @Test
    void javaOfflineReportDistinguishesEntityWorldNamesAndMalformedVersionedRoots()
            throws SQLException {
        try (Connection database = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            database.createStatement().execute(
                    "CREATE TABLE instance_current_state(location_type TEXT,location_key TEXT,state TEXT)");
            database.createStatement().execute(
                    "CREATE TABLE instance_observations(location_type TEXT,location_key TEXT,confidence TEXT)");
            insert(database, "BLOCK_CONTAINER", "minecraft:overworld:-1:70:8");
            insert(database, "BLOCK_CONTAINER", "block-v2:" + WORLD + ":-1:70:8");
            insert(database, "BLOCK_CONTAINER", "block-v2:not-a-uuid:-1:70:8");
            insert(database, "BLOCK_CONTAINER",
                    "double-chest:" + WORLD + ":15:70:8|16:70:8:left=1");
            insert(database, "BLOCK_CONTAINER",
                    "double-chest:" + WORLD + ":15:70:8|16:70:8:left=9");
            insert(database, "BLOCK_CONTAINER", "minecraft:overworld:entity:" + ENTITY);
            insert(database, "NESTED_CONTAINER",
                    "root:BLOCK_CONTAINER:minecraft:overworld:entity:" + ENTITY);
            database.createStatement().execute("PRAGMA query_only=ON");
            var result = SQLiteLegacyKeyDryRun.inspect(database, Map.of(
                    "minecraft:overworld", List.of(UUID.fromString(WORLD))));
            assertEquals(1L, result.current().legacyKnownName());
            assertEquals(1L, result.current().versionedBlock());
            assertEquals(1L, result.current().versionedDoubleChest());
            assertEquals(2L, result.current().worldNamedEntityInventory());
            assertEquals(2L, result.current().otherContainerKeys());
            assertEquals(0L, result.current().legacyAmbiguousName());
            assertEquals(0L, result.current().legacyUnknownName());
            assertEquals(result.current(), result.history());
        }
    }

    private static void insert(Connection database, String type, String key)
            throws SQLException {
        try (PreparedStatement current = database.prepareStatement(
                "INSERT INTO instance_current_state VALUES (?,?,'CONFIRMED_NOW')");
                PreparedStatement history = database.prepareStatement(
                        "INSERT INTO instance_observations VALUES (?,?,'CONFIRMED_NOW')")) {
            current.setString(1, type);
            current.setString(2, key);
            current.executeUpdate();
            history.setString(1, type);
            history.setString(2, key);
            history.executeUpdate();
        }
    }
}
