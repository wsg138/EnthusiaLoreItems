package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/** Ordinary non-air stacks must count against the promised item-visit budget. */
class PaperLeafScanBudgetTest {
    private static final int BUDGET = 256;
    private PluginMock plugin;
    private PaperTrackingCoordinator coordinator;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void ordinary256LeavesRemainCompleteWithoutFalseTruncation() {
        PaperScanLimit limit = new PaperScanLimit(BUDGET);
        Map<LoreItemIdentity, List<LocationDescriptor>> observations = new ConcurrentHashMap<>();
        new PaperTrackedItemCollector().collectArray(
                leaves(BUDGET, false), LocationDescriptor.Type.BLOCK_CONTAINER,
                "world:0:70:0", "slot:", observations, limit);
        assertTrue(limit.hasRemaining());
        assertTrue(observations.isEmpty());
    }

    @Test
    void leaf257MustNotBeDecodedOrClaimedCompleteByCollector() {
        PaperScanLimit limit = new PaperScanLimit(BUDGET);
        Map<LoreItemIdentity, List<LocationDescriptor>> observations = new ConcurrentHashMap<>();
        new PaperTrackedItemCollector().collectArray(
                leaves(BUDGET + 1, true), LocationDescriptor.Type.BLOCK_CONTAINER,
                "world:0:70:0", "slot:", observations, limit);
        assertFalse(limit.hasRemaining(), "A refused non-air leaf is an incomplete scan");
        assertTrue(observations.isEmpty());
    }

    @Test
    void directPhysicalScanAlsoSkipsIdentityDecodeBeyondBudget() {
        TrackingObservationUseCase useCase = request -> CompletableFuture.completedFuture(
                TrackingObservationUseCase.Result.of(
                        TrackingObservationUseCase.Status.RECORDED, "ok"));
        coordinator = new PaperTrackingCoordinator(plugin, () -> useCase, () -> 4, MetricsPort.noOp());
        PaperPhysicalInventoryScanner scanner = new PaperPhysicalInventoryScanner(coordinator);
        PaperScanLimit limit = new PaperScanLimit(BUDGET);
        ItemStack[] items = leaves(BUDGET + 1, true);
        for (int slot = 0; slot < items.length; slot++) {
            scanner.scanItemTree(
                    items[slot],
                    new LocationDescriptor(
                            LocationDescriptor.Type.BLOCK_CONTAINER,
                            "world:0:70:0", "slot:" + slot),
                    TrackingObservationUseCase.Presence.PRESENT,
                    TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                    "bounded-root-test",
                    limit);
        }
        assertFalse(limit.hasRemaining(), "Direct event traversal must bound leaf work");
    }

    private static ItemStack[] leaves(int count, boolean poisonLast) {
        ItemStack[] items = new ItemStack[count];
        ItemStack ordinary = ItemStack.of(Material.STONE);
        for (int slot = 0; slot < count; slot++) {
            items[slot] = ordinary;
        }
        if (poisonLast) {
            items[count - 1] = new ItemStack(Material.DIAMOND) {
                @Override
                public boolean hasItemMeta() {
                    throw new AssertionError("Budget-exhausted leaf should not be inspected");
                }

                @Override
                public ItemMeta getItemMeta() {
                    throw new AssertionError("Budget-exhausted leaf metadata should not be read");
                }
            };
        }
        return items;
    }
}
