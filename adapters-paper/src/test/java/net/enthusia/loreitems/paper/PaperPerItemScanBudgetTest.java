package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/** Root and nested ordinary leaves must respect the same scan work budget. */
class PaperPerItemScanBudgetTest {
    private static final String BLOCK_KEY = "world:12:65:12";
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
                BLOCK_KEY, "slot:", new ConcurrentHashMap<>(), limit);
        assertFalse(limit.hasRemaining(),
                "The 257th nonair root must fail closed instead of exceeding budget");
    }

    @Test
    void collectorAcceptsExactlyBudgetedOrdinaryRoots() {
        PaperScanLimit limit = new PaperScanLimit(54);
        new PaperTrackedItemCollector().collectArray(
                ordinaryItems(54), LocationDescriptor.Type.BLOCK_CONTAINER,
                BLOCK_KEY, "slot:", new ConcurrentHashMap<>(), limit);
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
                BLOCK_KEY, TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "bounded-root-proof", partial);
        assertFalse(partial.hasRemaining(), "54 leaf items cannot fit in 40 work units");

        PaperScanLimit complete = new PaperScanLimit(54);
        scanner.scanInventory(inventory, LocationDescriptor.Type.BLOCK_CONTAINER,
                BLOCK_KEY, TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "complete-root-proof", complete);
        assertTrue(complete.hasRemaining());
    }

    @Test
    void nestedLeavesSpendBudgetBeforeReachingLateTrackedItem() {
        ItemStack nestedShulker = ItemStack.of(Material.SHULKER_BOX);
        BlockStateMeta meta = assertInstanceOf(
                BlockStateMeta.class, nestedShulker.getItemMeta());
        ShulkerBox box = assertInstanceOf(ShulkerBox.class, meta.getBlockState());
        ItemStack[] contents = ordinaryItems(27);
        LoreItemIdentity identity = new LoreItemIdentity(
                new LoreDefinitionId(
                        UUID.fromString("11111111-1111-1111-1111-111111111111")),
                new LoreInstanceId(
                        UUID.fromString("22222222-2222-2222-2222-222222222222")),
                new TemplateRevision(1));
        contents[26] = new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.NETHER_STAR), identity);
        box.getInventory().setContents(contents);
        meta.setBlockState(box);
        assertTrue(nestedShulker.setItemMeta(meta));

        PaperScanLimit collectorLimit = new PaperScanLimit(10);
        new PaperTrackedItemCollector().collectItem(
                nestedShulker, LocationDescriptor.Type.BLOCK_CONTAINER,
                BLOCK_KEY, "slot:0", new ConcurrentHashMap<>(),
                0, collectorLimit);
        assertFalse(collectorLimit.hasRemaining(),
                "The collector must not skip 26 ordinary nested leaves for free");

        coordinator = new PaperTrackingCoordinator(
                plugin, () -> request -> CompletableFuture.completedFuture(
                        TrackingObservationUseCase.Result.of(
                                TrackingObservationUseCase.Status.RECORDED, "ok")),
                () -> 4, MetricsPort.noOp());
        PaperPhysicalInventoryScanner scanner = new PaperPhysicalInventoryScanner(coordinator);
        PaperScanLimit limit = new PaperScanLimit(10);
        scanner.scanItemTree(nestedShulker,
                new LocationDescriptor(LocationDescriptor.Type.BLOCK_CONTAINER,
                        BLOCK_KEY, "slot:0"),
                TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "nested-bounded", limit);
        assertFalse(limit.hasRemaining(),
                "Physical scanning must count ordinary nested leaves as bounded work");
    }

    private static ItemStack[] ordinaryItems(int count) {
        ItemStack[] contents = new ItemStack[count];
        Arrays.fill(contents, ItemStack.of(Material.STONE));
        return contents;
    }
}
