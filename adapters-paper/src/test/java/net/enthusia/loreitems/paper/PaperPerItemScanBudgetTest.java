package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/** Root and nested ordinary leaves must respect the same scan work budget. */
class PaperPerItemScanBudgetTest {
    private ServerMock server;
    private PluginMock plugin;
    private PaperTrackingCoordinator coordinator;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
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
    void collectorNeverReportsCompleteFor257OrdinaryRootsUnder256ItemLimit() {
        PaperTrackedItemCollector collector = new PaperTrackedItemCollector();
        ItemStack[] contents = ordinaryItems(257);
        PaperScanLimit limit = new PaperScanLimit(256);
        collector.collectArray(contents, LocationDescriptor.Type.BLOCK_CONTAINER,
                "world:12:65:12", "slot:", new ConcurrentHashMap<>(), limit);
        assertFalse(limit.hasRemaining(),
                "The 257th nonair root must fail closed instead of exceeding budget");
    }

    @Test
    void collectorAcceptsExactlyBudgetedOrdinaryRoots() {
        PaperScanLimit limit = new PaperScanLimit(54);
        new PaperTrackedItemCollector().collectArray(
                ordinaryItems(54), LocationDescriptor.Type.BLOCK_CONTAINER,
                "world:12:65:12", "slot:", new ConcurrentHashMap<>(), limit);
        assertTrue(limit.hasRemaining(), "Exactly 54 examined root slots are complete");
    }

    @Test
    void ordinaryInventoryScannerCountsEachNonairRoot() {
        coordinator = new PaperTrackingCoordinator(
                plugin, () -> request -> CompletableFuture.completedFuture(
                        TrackingObservationUseCase.Result.of(
                                TrackingObservationUseCase.Status.RECORDED, "ok")),
                () -> 4, MetricsPort.noOp());
        PaperPhysicalInventoryScanner scanner = new PaperPhysicalInventoryScanner(coordinator);
        Inventory inventory = server.createInventory(null, 54);
        inventory.setContents(ordinaryItems(54));

        PaperScanLimit partial = new PaperScanLimit(40);
        scanner.scanInventory(inventory, LocationDescriptor.Type.BLOCK_CONTAINER,
                "world:12:65:12", TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "bounded-root-proof", partial);
        assertFalse(partial.hasRemaining(), "54 leaf items cannot fit in 40 work units");

        PaperScanLimit complete = new PaperScanLimit(54);
        scanner.scanInventory(inventory, LocationDescriptor.Type.BLOCK_CONTAINER,
                "world:12:65:12", TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "complete-root-proof", complete);
        assertTrue(complete.hasRemaining());
    }

    private static ItemStack[] ordinaryItems(int count) {
        ItemStack[] contents = new ItemStack[count];
        Arrays.fill(contents, ItemStack.of(Material.STONE));
        return contents;
    }
}
