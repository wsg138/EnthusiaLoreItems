package net.enthusia.loreitems.sqlite;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.LoreItemIdentity;

/**
 * Records an unresolved disappearance following a Creative inventory mutation.
 * Never marks a still-unlocated physical instance REMOVED or VOID_DESTROYED.
 */
public final class SQLiteCreativeInventoryLossStore {
    private static final String EVENT = "creative_inventory_missing_unresolved";
    private static final long DEDUPLICATE_MILLIS = 5_000L;
    private final SQLiteStorageRuntime storage;
    private final Clock clock;

    public SQLiteCreativeInventoryLossStore(SQLiteStorageRuntime storage) {
        this(storage, Clock.systemUTC());
    }

    SQLiteCreativeInventoryLossStore(SQLiteStorageRuntime storage, Clock clock) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CompletionStage<Boolean> record(UUID playerId, LoreItemIdentity identity) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(identity, "identity");
        long now = clock.millis();
        return storage.execute(connection -> SQLiteTransactions.inTransaction(connection, tx -> {
            String instance = identity.instanceId().value().toString();
            try (PreparedStatement check = tx.prepareStatement(
                    "SELECT 1 FROM lore_instances "
                    + "WHERE instance_id = ? AND definition_id = ? AND lifecycle_state = 'ACTIVE'")) {
                check.setString(1, instance);
                check.setString(2, identity.definitionId().value().toString());
                try (ResultSet rows = check.executeQuery()) {
                    if (!rows.next()) {
                        return false;
                    }
                }
            }
            try (PreparedStatement check = tx.prepareStatement(
                    "SELECT 1 FROM audit_events WHERE aggregate_type = 'lore_instance' "
                    + "AND aggregate_id = ? AND event_type = ? AND occurred_at >= ? LIMIT 1")) {
                check.setString(1, instance);
                check.setString(2, EVENT);
                check.setLong(3, now - DEDUPLICATE_MILLIS);
                try (ResultSet rows = check.executeQuery()) {
                    if (rows.next()) {
                        return false;
                    }
                }
            }
            // A location change to a chest or another player is not a deletion.
            // Only replace a state still claiming this player's inventory.
            long revision;
            try (PreparedStatement check = tx.prepareStatement(
                    "SELECT state_revision FROM instance_current_state WHERE instance_id = ? "
                    + "AND state IN ('CONFIRMED_NOW', 'LAST_CONFIRMED') "
                    + "AND location_type = 'PLAYER_INVENTORY' AND location_key = ? "
                    + "AND updated_at <= ?")) {
                check.setString(1, instance);
                check.setString(2, "player:" + playerId);
                check.setLong(3, now);
                try (ResultSet rows = check.executeQuery()) {
                    if (!rows.next()) {
                        return false;
                    }
                    revision = rows.getLong(1);
                }
            }
            try (PreparedStatement update = tx.prepareStatement(
                    "UPDATE instance_current_state SET state = 'MISSING_UNRESOLVED', "
                    + "location_type = NULL, location_key = NULL, container_path = NULL, "
                    + "last_observation_id = NULL, state_revision = state_revision + 1, "
                    + "updated_at = ? WHERE instance_id = ? AND state_revision = ?")) {
                update.setLong(1, now);
                update.setString(2, instance);
                update.setLong(3, revision);
                if (update.executeUpdate() != 1) {
                    return false;
                }
            }
            try (PreparedStatement insert = tx.prepareStatement(
                    "INSERT INTO audit_events(aggregate_type, aggregate_id, event_type, "
                    + "actor_type, actor_id, detail_json, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, "lore_instance");
                insert.setString(2, instance);
                insert.setString(3, EVENT);
                insert.setString(4, "player");
                insert.setString(5, playerId.toString());
                insert.setString(6, "{\"source\":\"creative-inventory\",\"status\":\"unresolved\","
                        + "\"detail\":\"Not visible after a creative inventory action; may have been deleted or moved elsewhere\"}");
                insert.setLong(7, now);
                return insert.executeUpdate() == 1;
            }
        }));
    }
}
