package net.enthusia.loreitems.sqlite;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.enthusia.loreitems.domain.LocationDescriptor;

/** Conservative location-identity boundary until issue #103 migration is proved. */
final class SQLiteTrackingLegacyKeyFence {
    private static final Pattern LEGACY_WORLD_BLOCK = Pattern.compile(
            "([a-z0-9_.-]+:[a-z0-9_./-]+):-?[0-9]+:-?[0-9]+:-?[0-9]+");

    private SQLiteTrackingLegacyKeyFence() {}

    static boolean needsReview(LocationDescriptor previous, LocationDescriptor observed) {
        if (previous == null || observed == null
                || previous.type() != observed.type()) {
            return false;
        }
        return root(previous) != null && root(observed) != null
                && legacyWorldName(previous.locationKey()) != null
                && isVersionedRoot(root(observed))
                && !previous.locationKey().equals(observed.locationKey());
    }

    private static boolean isVersionedRoot(String key) {
        return key.startsWith("block-v2:") || key.startsWith("double-chest:");
    }

    private static String root(LocationDescriptor location) {
        return switch (location.type()) {
            case BLOCK_CONTAINER -> location.locationKey();
            case NESTED_CONTAINER -> location.locationKey().startsWith("root:BLOCK_CONTAINER:")
                    ? rootKey(location.locationKey()) : null;
            default -> null;
        };
    }

    private static String rootKey(String key) {
        return key.startsWith("root:BLOCK_CONTAINER:")
                ? key.substring("root:BLOCK_CONTAINER:".length()) : key;
    }

    /** The world *name* is diagnostic only, never a trustworthy UUID mapping. */
    static String legacyWorldName(String locationKey) {
        Matcher matcher = LEGACY_WORLD_BLOCK.matcher(rootKey(locationKey));
        return matcher.matches() ? matcher.group(1) : null;
    }
}
