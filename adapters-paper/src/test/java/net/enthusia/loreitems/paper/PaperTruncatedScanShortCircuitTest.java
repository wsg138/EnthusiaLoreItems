package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/** Incomplete evidence must not trigger further scanning or new submissions. */
class PaperTruncatedScanShortCircuitTest {
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
    void collectorStopsReadingLaterRootAndNestedItemsAfterTruncation() {
        ItemStack tracked = trackedItem();
        PaperTrackedItemCollector collector = new PaperTrackedItemCollector();
        PaperScanLimit limit = new PaperScanLimit(2);
        HashMap<LoreItemIdentity, List<LocationDescriptor>> observations = new HashMap<>();

        limit.markTruncated();
        collector.collectArray(new ItemStack[] {tracked, tracked},
                LocationDescriptor.Type.PLAYER_INVENTORY, "player:synthetic",
                "slot:", observations, limit);
        collector.collectItem(tracked, LocationDescriptor.Type.NESTED_CONTAINER,
                "root:PLAYER_INVENTORY:synthetic", "slot:0/shulker:0",
                observations, 8, limit);

        assertTrue(observations.isEmpty(),
                "No new unique identity proof may be recorded after truncation");
        assertFalse(limit.hasRemaining());
    }

    @Test
    void scannerDoesNotSubmitAnyRootOrSingleItemAfterTruncation() {
        List<TrackingObservationUseCase.Request> observed = new CopyOnWriteArrayList<>();
        TrackingObservationUseCase useCase = request -> {
            observed.add(request);
            return CompletableFuture.completedFuture(TrackingObservationUseCase.Result.of(
                    TrackingObservationUseCase.Status.RECORDED, "ok"));
        };
        coordinator = new PaperTrackingCoordinator(
                plugin, () -> useCase, () -> 4, MetricsPort.noOp());
        PaperPhysicalInventoryScanner scanner = new PaperPhysicalInventoryScanner(coordinator);
        ItemStack tracked = trackedItem();
        Inventory inventory = server.createInventory(null, 9);
        inventory.setItem(0, tracked);
        PaperScanLimit limit = new PaperScanLimit(1);

        limit.markTruncated();
        scanner.scanInventory(inventory, LocationDescriptor.Type.BLOCK_CONTAINER,
                "block-v2:00000000-0000-0000-0000-000000000000:0:64:0",
                TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION,
                "truncated-inventory", limit);
        scanner.scanItemTree(tracked,
                new LocationDescriptor(LocationDescriptor.Type.PLAYER_INVENTORY,
                        "player:synthetic", "slot:0"),
                TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION,
                "truncated-single-item", limit);

        assertEquals(0, observed.size(),
                "Proof that is already incomplete cannot submit later authoritative records");
        assertFalse(limit.hasRemaining());
    }

    private static ItemStack trackedItem() {
        LoreItemIdentity identity = new LoreItemIdentity(
                new LoreDefinitionId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                new LoreInstanceId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
                new TemplateRevision(1));
        return new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.NETHER_STAR), identity);
    }
}
