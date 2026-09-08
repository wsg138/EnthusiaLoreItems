package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.junit.jupiter.api.Test;

class PaperNestedLocationIdentityTest {
    @Test
    void playerInventoryAndEnderChestNestedPathsRemainDistinct() {
        String playerKey = "player:" + UUID.randomUUID();
        LocationDescriptor inventoryRoot = new LocationDescriptor(
                LocationDescriptor.Type.PLAYER_INVENTORY,
                playerKey,
                "slot:0");
        LocationDescriptor enderRoot = new LocationDescriptor(
                LocationDescriptor.Type.PLAYER_ENDER_CHEST,
                playerKey,
                "slot:0");

        LocationDescriptor inventoryNested =
                PaperPhysicalInventoryScanner.nestedLocation(inventoryRoot, "bundle", 0);
        LocationDescriptor enderNested =
                PaperPhysicalInventoryScanner.nestedLocation(enderRoot, "bundle", 0);

        assertEquals("slot:0/bundle:0", inventoryNested.containerPath());
        assertEquals("slot:0/bundle:0", enderNested.containerPath());
        assertNotEquals(inventoryNested.locationKey(), enderNested.locationKey());
        assertNotEquals(inventoryNested, enderNested);
    }

    @Test
    void deeperNestingPreservesTheOriginalRootQualifier() {
        String playerKey = "player:" + UUID.randomUUID();
        LocationDescriptor root = new LocationDescriptor(
                LocationDescriptor.Type.PLAYER_ENDER_CHEST,
                playerKey,
                "slot:4");
        LocationDescriptor first =
                PaperPhysicalInventoryScanner.nestedLocation(root, "shulker", 2);

        LocationDescriptor second =
                PaperPhysicalInventoryScanner.nestedLocation(first, "bundle", 1);

        assertEquals(first.locationKey(), second.locationKey());
        assertEquals("slot:4/shulker:2/bundle:1", second.containerPath());
    }
}
