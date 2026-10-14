package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Explicit, read-only offline audit. Counts rows, not unique physical inventories.
 * A currently unique world-name mapping is NOT proof of a historical UUID mapping.
 * This utility never authorizes a rewrite, projection promotion or forced chunk load.
 */
public final class SQLiteLegacyKeyDryRun {
    private static final String UUID_TEXT = "[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}";
    private static final Pattern BLOCK_V2 = Pattern.compile(
            "block-v2:" + UUID_TEXT + ":-?[0-9]+:-?[0-9]+:-?[0-9]+");
    private static final Pattern DOUBLE_CHEST = Pattern.compile(
            "double-chest:" + UUID_TEXT
                    + ":-?[0-9]+:-?[0-9]+:-?[0-9]+\\|-?[0-9]+:-?[0-9]+:-?[0-9]+:left=[01]");
    private static final Pattern WORLD_NAMED_ENTITY = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9_./-]+:entity:" + UUID_TEXT);
    private static final String CURRENT = "SELECT location_key, state "
            + "FROM instance_current_state WHERE location_type IN ('BLOCK_CONTAINER', 'NESTED_CONTAINER')";
    private static final String HISTORY = "SELECT location_key, confidence "
            + "FROM instance_observations WHERE location_type IN ('BLOCK_CONTAINER', 'NESTED_CONTAINER')";

    private SQLiteLegacyKeyDryRun() {}

    public static Report inspect(
            Connection connection, Map<String, ? extends Collection<UUID>> knownWorlds)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(knownWorlds, "knownWorlds");
        return new Report(scan(connection, knownWorlds, true),
                scan(connection, knownWorlds, false));
    }

    private static Counts scan(Connection connection,
            Map<String, ? extends Collection<UUID>> worlds, boolean current)
            throws SQLException {
        MutableCounts counts = new MutableCounts();
        // These are the two fixed audit statements: callers cannot supply SQL.
        // A prepared statement also prevents an accidental future caller from
        // interpolating user-controlled world names into the SQL itself.
        try (PreparedStatement statement =
                     connection.prepareStatement(current ? CURRENT : HISTORY);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String key = rows.getString(1);
                String state = rows.getString(2);
                counts.accept(key, state, worlds, current);
            }
        }
        return counts.snapshot();
    }

    public record Report(Counts current, Counts history) {}

    public record Counts(
            long legacyKnownName, long legacyAmbiguousName, long legacyUnknownName,
            long versionedBlock, long versionedDoubleChest, long otherContainerKeys,
            long lastConfirmedLegacy, long worldNamedEntityInventory) {
        public long totalLegacy() {
            return legacyKnownName + legacyAmbiguousName + legacyUnknownName;
        }
    }

    private static final class MutableCounts {
        private long known;
        private long ambiguous;
        private long unknown;
        private long blocks;
        private long doubles;
        private long other;
        private long lastConfirmed;
        private long worldNamedEntity;

        void accept(String key, String state,
                Map<String, ? extends Collection<UUID>> worlds, boolean current) {
            if (key == null) {
                other++;
                return;
            }
            String root = key.startsWith("root:BLOCK_CONTAINER:")
                    ? key.substring("root:BLOCK_CONTAINER:".length()) : key;
            if (BLOCK_V2.matcher(root).matches()) {
                blocks++;
                return;
            }
            if (DOUBLE_CHEST.matcher(root).matches()) {
                doubles++;
                return;
            }
            if (WORLD_NAMED_ENTITY.matcher(root).matches()) {
                // Existing production entity-holder keys embed world names.
                // Report separately; they are not x:y:z block migration candidates.
                worldNamedEntity++;
                return;
            }
            String name = SQLiteTrackingLegacyKeyFence.legacyWorldName(key);
            if (name == null) {
                other++;
                return;
            }
            if (current && "LAST_CONFIRMED".equals(state)) {
                lastConfirmed++;
            }
            countName(worlds.get(name));
        }

        private void countName(Collection<UUID> candidates) {
            long distinct = candidates == null ? 0L
                    : candidates.stream().filter(Objects::nonNull).distinct().limit(2).count();
            if (distinct == 0L) {
                unknown++;
            } else if (distinct == 1L) {
                known++;
            } else {
                ambiguous++;
            }
        }

        Counts snapshot() {
            return new Counts(known, ambiguous, unknown, blocks, doubles, other,
                    lastConfirmed, worldNamedEntity);
        }
    }
}
