package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/** Human-readable locations for staff; never exposes raw database keys by default. */
final class PaperFriendlyLocation {
    private static final String ENTITY_MARKER = ":entity:";
    private final Plugin plugin;

    PaperFriendlyLocation(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    String describe(LocationDescriptor location) {
        if (location == null) {
            return "Location unknown";
        }
        return switch (location.type()) {
            case PLAYER_INVENTORY -> playerName(location.locationKey()) + "'s inventory";
            case PLAYER_ENDER_CHEST -> playerName(location.locationKey()) + "'s Ender Chest";
            case BLOCK_CONTAINER -> blockContainer(location.locationKey());
            case NESTED_CONTAINER -> nestedContainer(location.locationKey());
            case DROPPED_ITEM -> entityPlace(location.locationKey(), "Dropped on the ground");
            case ITEM_FRAME -> entityPlace(location.locationKey(), "In an item frame");
            case ITEM_DISPLAY -> entityPlace(location.locationKey(), "In an item display");
            case ARMOR_STAND -> entityPlace(location.locationKey(), "On an armor stand");
            case QUEUED_DELIVERY -> "Waiting to be delivered";
            case PENDING_MUTATION -> "Being updated";
            case VOID_DESTROYED -> "Destroyed in the void";
            case DUPLICATE_CONFLICT -> "Multiple possible locations - needs review";
        };
    }

    private String playerName(String key) {
        try {
            if (!key.startsWith("player:")) {
                return "Unknown player";
            }
            UUID playerId = UUID.fromString(key.substring("player:".length()));
            OfflinePlayer player = plugin.getServer().getOfflinePlayer(playerId);
            return player != null && player.getName() != null && !player.getName().isBlank()
                    ? player.getName() : "Unknown player";
        } catch (IllegalArgumentException exception) {
            return "Unknown player";
        }
    }

    private String nestedContainer(String key) {
        if (key.startsWith("root:PLAYER_INVENTORY:")) {
            return playerName(key.substring("root:PLAYER_INVENTORY:".length())) + "'s carried container";
        }
        if (key.startsWith("root:PLAYER_ENDER_CHEST:")) {
            return playerName(key.substring("root:PLAYER_ENDER_CHEST:".length())) + "'s Ender Chest container";
        }
        if (key.startsWith("root:BLOCK_CONTAINER:")) {
            return "Inside a container at " + blockContainer(
                    key.substring("root:BLOCK_CONTAINER:".length()));
        }
        return "Inside a shulker or bundle";
    }

    private String blockContainer(String key) {
        int entityAt = key.indexOf(ENTITY_MARKER);
        if (entityAt >= 0) {
            return entityPlace(key, "In a mob or vehicle inventory");
        }
        Coordinates coords = coordinates(key);
        if (coords == null) {
            return "In a container (position unavailable)";
        }
        World world = world(coords.worldKey());
        String kind = "Container";
        if (world != null && world.isChunkLoaded(coords.x() >> 4, coords.z() >> 4)
                && coords.y() >= world.getMinHeight() && coords.y() < world.getMaxHeight()) {
            Material block = world.getBlockAt(coords.x(), coords.y(), coords.z()).getType();
            kind = switch (block) {
                case CHEST, TRAPPED_CHEST -> "Chest";
                case BARREL -> "Barrel";
                case HOPPER -> "Hopper";
                case DISPENSER -> "Dispenser";
                case DROPPER -> "Dropper";
                default -> "Container";
            };
        }
        return kind + " at " + coords.x() + ", " + coords.y() + ", " + coords.z()
                + " (" + worldLabel(world, coords.worldKey()) + ")";
    }

    private String entityPlace(String key, String fallback) {
        int index = key.indexOf(ENTITY_MARKER);
        if (index < 0) {
            return fallback;
        }
        String rest = key.substring(index + ENTITY_MARKER.length());
        if (rest.length() >= 36) {
            try {
                Entity entity = plugin.getServer().getEntity(UUID.fromString(rest.substring(0, 36)));
                if (entity != null) {
                    return fallback + " at "
                            + entity.getLocation().getBlockX() + ", "
                            + entity.getLocation().getBlockY() + ", "
                            + entity.getLocation().getBlockZ()
                            + " (" + entity.getWorld().getName() + ")";
                }
            } catch (IllegalArgumentException exception) {
                // Older location data can contain an invalid or absent entity identifier.
            }
        }
        Coordinates coords = coordinates(key);
        if (coords == null) {
            return fallback + " (last known position unavailable)";
        }
        String worldKey = coords.worldKey();
        int marker = worldKey.indexOf(ENTITY_MARKER);
        if (marker >= 0) {
            worldKey = worldKey.substring(0, marker);
        }
        return fallback + " near " + coords.x() + ", " + coords.y() + ", "
                + coords.z() + " (" + worldLabel(world(worldKey), worldKey) + ")";
    }

    private static Coordinates coordinates(String key) {
        int c = key.lastIndexOf(':');
        if (c < 0) {
            return null;
        }
        int b = key.lastIndexOf(':', c - 1);
        int a = key.lastIndexOf(':', b - 1);
        if (a < 0 || b < 0) {
            return null;
        }
        try {
            return new Coordinates(
                    key.substring(0, a),
                    Integer.parseInt(key.substring(a + 1, b)),
                    Integer.parseInt(key.substring(b + 1, c)),
                    Integer.parseInt(key.substring(c + 1)));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private World world(String key) {
        NamespacedKey namespaced = NamespacedKey.fromString(key);
        return namespaced == null ? null : plugin.getServer().getWorld(namespaced);
    }

    private static String worldLabel(World world, String key) {
        if (world != null) {
            return world.getName();
        }
        return key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
    }

    private record Coordinates(String worldKey, int x, int y, int z) {}
}
