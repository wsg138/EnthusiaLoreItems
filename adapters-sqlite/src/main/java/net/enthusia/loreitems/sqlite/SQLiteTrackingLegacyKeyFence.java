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
                || previous.type() != LocationDescriptor.Type.BLOCK_CONTAINER
                || observed.type() != LocationDescriptor.Type.BLOCK_CONTAINER) {
            return false;
        }
        return legacyWorldName(previous.locationKey()) != null
                && (observed.locationKey().startsWith("block-v2:")
                        || observed.locationKey().startsWith("double-chest:"))
                && !previous.locationKey().equals(observed.locationKey());
    }

    /** The world *name* is diagnostic only, never a trustworthy UUID mapping. */
    static String legacyWorldName(String locationKey) {
        Matcher matcher = LEGACY_WORLD_BLOCK.matcher(locationKey);
        return matcher.matches() ? matcher.group(1) : null;
    }
}
