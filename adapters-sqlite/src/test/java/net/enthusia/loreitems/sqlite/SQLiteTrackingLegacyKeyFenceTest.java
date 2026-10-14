package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.enthusia.loreitems.domain.LocationDescriptor;
import org.junit.jupiter.api.Test;

/** Regression for false duplicate fencing of ordinary UUID-based inventory movement. */
class SQLiteTrackingLegacyKeyFenceTest {
    private static final String WORLD = "dd7fcec6-9bfc-486c-bbe8-e282316ebaec";

    @Test
    void hopperToChestWithinSameUuidWorldMustNotTriggerLegacyReview() {
        LocationDescriptor hopper = block("block-v2:" + WORLD + ":20:70:0", "slot:0");
        LocationDescriptor chest = block("block-v2:" + WORLD + ":20:69:0", "slot:0");

        assertNull(SQLiteTrackingLegacyKeyFence.legacyWorldName(hopper.locationKey()));
        assertNull(SQLiteTrackingLegacyKeyFence.legacyWorldName(chest.locationKey()));
        assertFalse(SQLiteTrackingLegacyKeyFence.needsReview(hopper, chest));
    }

    @Test
    void legitimateModernContainerMoveAcrossWorldUuidsIsNotLegacyAmbiguity() {
        LocationDescriptor original = block("block-v2:" + WORLD + ":5:64:5", "slot:0");
        LocationDescriptor destination = block(
                "block-v2:11111111-2222-3333-4444-555555555555:5:64:5", "slot:0");

        assertFalse(SQLiteTrackingLegacyKeyFence.needsReview(original, destination));
    }

    @Test
    void historicalWorldNameStillNeedsReviewBeforeVersionedMove() {
        LocationDescriptor historical = block("minecraft:world:20:70:0", "slot:0");
        LocationDescriptor current = block("block-v2:" + WORLD + ":20:70:0", "slot:0");

        assertEquals("minecraft:world",
                SQLiteTrackingLegacyKeyFence.legacyWorldName(historical.locationKey()));
        assertTrue(SQLiteTrackingLegacyKeyFence.needsReview(historical, current));
    }

    @Test
    void historicalNestedShulkerKeyStillRequiresReview() {
        LocationDescriptor historical = new LocationDescriptor(
                LocationDescriptor.Type.NESTED_CONTAINER,
                "root:BLOCK_CONTAINER:minecraft:world:20:70:0", "slot:0/shulker:0");
        LocationDescriptor current = new LocationDescriptor(
                LocationDescriptor.Type.NESTED_CONTAINER,
                "root:BLOCK_CONTAINER:block-v2:" + WORLD + ":20:70:0",
                "slot:0/shulker:0");

        assertTrue(SQLiteTrackingLegacyKeyFence.needsReview(historical, current));
        assertNull(SQLiteTrackingLegacyKeyFence.legacyWorldName(current.locationKey()));
        assertFalse(SQLiteTrackingLegacyKeyFence.needsReview(current, historical));
    }

    @Test
    void modernNestedShulkerMovesAreNotLegacyAmbiguity() {
        LocationDescriptor first = new LocationDescriptor(
                LocationDescriptor.Type.NESTED_CONTAINER,
                "root:BLOCK_CONTAINER:block-v2:" + WORLD + ":20:70:0",
                "slot:0/shulker:0");
        LocationDescriptor second = new LocationDescriptor(
                LocationDescriptor.Type.NESTED_CONTAINER,
                "root:BLOCK_CONTAINER:block-v2:" + WORLD + ":20:69:0",
                "slot:0/shulker:0");

        assertFalse(SQLiteTrackingLegacyKeyFence.needsReview(first, second));
    }

    @Test
    void modernToDoubleChestUsesNormalDuplicatePolicyNotLegacyReview() {
        LocationDescriptor modern = block("block-v2:" + WORLD + ":15:70:8", "slot:0");
        LocationDescriptor pair = block(
                "double-chest:" + WORLD + ":15:70:8|16:70:8:left=1", "slot:0");

        assertFalse(SQLiteTrackingLegacyKeyFence.needsReview(modern, pair));
        assertNull(SQLiteTrackingLegacyKeyFence.legacyWorldName(pair.locationKey()));
    }

    private static LocationDescriptor block(String key, String slot) {
        return new LocationDescriptor(LocationDescriptor.Type.BLOCK_CONTAINER, key, slot);
    }
}
