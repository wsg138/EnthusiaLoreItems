package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/** A maximum-depth container is incomplete evidence, never authoritative absence. */
class PaperNestedDepthFailClosedTest {
    private static final int MAX_NESTING_DEPTH = 8;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void metadataBearingShulkerAtMaximumDepthInvalidatesFullObservation() {
        ItemStack item = ItemStack.of(Material.SHULKER_BOX);
        BlockStateMeta meta = assertInstanceOf(BlockStateMeta.class, item.getItemMeta());
        ShulkerBox box = assertInstanceOf(ShulkerBox.class, meta.getBlockState());
        box.getInventory().setItem(0, ItemStack.of(Material.STONE));
        meta.setBlockState(box);
        assertTrue(item.setItemMeta(meta));

        PaperScanLimit limit = new PaperScanLimit(256);
        new PaperTrackedItemCollector().collectItem(
                item, LocationDescriptor.Type.NESTED_CONTAINER, "root:BLOCK_CONTAINER:world",
                "slot:0/shulker:0", new HashMap<>(), MAX_NESTING_DEPTH, limit);

        assertFalse(limit.hasRemaining(),
                "Any unvisited nested-capable metadata at the depth cap invalidates completeness");
    }

    @Test
    void unexaminedNestedEvidenceCannotInheritAuthoritativeMode() {
        assertEquals(
                net.enthusia.loreitems.application.TrackingObservationUseCase.EvidenceMode
                        .RECONCILIATION,
                PaperPhysicalInventoryScanner.safeModeAtDepth(
                        net.enthusia.loreitems.application.TrackingObservationUseCase.EvidenceMode
                                .AUTHORITATIVE_TRANSITION,
                        true));
        assertEquals(
                net.enthusia.loreitems.application.TrackingObservationUseCase.EvidenceMode
                        .AUTHORITATIVE_TRANSITION,
                PaperPhysicalInventoryScanner.safeModeAtDepth(
                        net.enthusia.loreitems.application.TrackingObservationUseCase.EvidenceMode
                                .AUTHORITATIVE_TRANSITION,
                        false));
    }

    @Test
    void ordinaryLeafAtMaximumDepthDoesNotInventTruncation() {
        PaperScanLimit limit = new PaperScanLimit(256);
        new PaperTrackedItemCollector().collectItem(
                ItemStack.of(Material.STONE),
                LocationDescriptor.Type.NESTED_CONTAINER, "root:BLOCK_CONTAINER:world",
                "slot:0/shulker:0", new HashMap<>(), MAX_NESTING_DEPTH, limit);
        assertTrue(limit.hasRemaining(), "A plain leaf contains no deeper inventory");
    }

    @Test
    void ExplicitlyMarkedIncompleteLimitNeverBecomesCompleteAgain() {
        PaperScanLimit limit = new PaperScanLimit(3);
        assertTrue(limit.tryConsume());
        limit.markTruncated();
        assertFalse(limit.tryConsume(),
                "A previously truncated scan must never grant another work reservation");
        assertFalse(limit.hasRemaining(), "Exhausting confidence is irreversible");
    }
}
