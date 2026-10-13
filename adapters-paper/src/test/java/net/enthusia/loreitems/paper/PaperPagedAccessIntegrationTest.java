package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/** Split from PaperBoundedContinuationTest with the same coverage and assertions. */
class PaperPagedAccessIntegrationTest {
    private static final String KEY = "minecraft:overworld:0:70:15";
    private static final LocationDescriptor.Type TYPE = LocationDescriptor.Type.BLOCK_CONTAINER;
    private static final LoreItemIdentity ID = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));
    private ServerMock server;

    @BeforeEach
    void setup() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void teardown() {
        MockBukkit.unmock();
    }

    @Test
    void scannerSubmitsCopiesFromSeparatePagesOnlyAsReconciliation() {
        var plugin = MockBukkit.createMockPlugin();
        List<TrackingObservationUseCase.Request> recorded = new CopyOnWriteArrayList<>();
        TrackingObservationUseCase useCase = request -> {
            recorded.add(request);
            return CompletableFuture.completedFuture(TrackingObservationUseCase.Result.of(
                    TrackingObservationUseCase.Status.RECORDED, "ok"));
        };
        PaperTrackingCoordinator coordinator = new PaperTrackingCoordinator(
                plugin, () -> useCase, () -> 4, MetricsPort.noOp());
        try {
            PaperPhysicalInventoryScanner scanner = new PaperPhysicalInventoryScanner(coordinator);
            Inventory inventory = server.createInventory(null, 18);
            inventory.setItem(0, tracked(ID));
            for (int slot = 1; slot <= 9; slot++) {
                inventory.setItem(slot, filledShulker(-1));
            }
            inventory.setItem(10, filledShulker(26));
            PaperBoundedInventoryContinuation.Cursor cursor = null;
            for (int page = 0; page < 4; page++) {
                var result = scanner.scanBounded(
                        inventory, TYPE, KEY, 77L, 9L, "stable-loaded-single",
                        cursor, new PaperScanLimit(256));
                scanner.submitPageObservations(result, "paged-reconciliation");
                cursor = result.nextCursor();
                if (cursor == null) break;
            }
            assertEquals(2, recorded.size());
            assertEquals(2, recorded.stream().filter(
                    r -> r.identity().equals(ID)).count());
            assertTrue(recorded.stream().allMatch(r -> r.mode()
                    == TrackingObservationUseCase.EvidenceMode.RECONCILIATION));
        } finally {
            coordinator.close();
        }
    }


    @Test
    void pagedPlayerCursorAndOpenContainerFindLateCopyWithExactPaths() {
        var player = server.addPlayer("PagedAccessTest");
        player.getInventory().setItem(0, tracked(ID));
        player.setItemOnCursor(tracked(ID));
        Inventory chest = server.createInventory(null, 18);
        for (int slot = 0; slot < 9; slot++) {
            chest.setItem(slot, filledShulker(-1));
        }
        chest.setItem(9, filledShulker(26));
        PaperPagedInventoryAccess pager = new PaperPagedInventoryAccess();
        PaperPagedInventoryAccess.Cursor cursor = null;
        List<String> locations = new java.util.ArrayList<>();
        int pages = 0;
        PaperPagedInventoryAccess.Batch batch;
        do {
            List<PaperPagedInventoryAccess.Source> sources =
                    new java.util.ArrayList<>(PaperPagedInventoryAccess.playerSources(player));
            sources.add(PaperPagedInventoryAccess.containerSource(
                    chest, TYPE, KEY, "loaded-chest"));
            batch = pager.scan(sources, 101L, 9L, cursor, new PaperScanLimit(256));
            assertTrue(batch.visited() <= 256);
            batch.observations().getOrDefault(ID, List.of()).stream()
                    .map(LocationDescriptor::containerPath).forEach(locations::add);
            cursor = batch.next();
            pages++;
        } while (cursor != null && pages < 8);
        assertEquals(2, pages);
        assertTrue(batch.coverageComplete());
        assertEquals(List.of("slot:0", "cursor", "slot:9/shulker:26"), locations);
    }

    @Test
    void pagedPlayerAccessRejectsSourceReorderOrDirtyFence() {
        PaperPagedInventoryAccess pager = new PaperPagedInventoryAccess();
        ItemStack[] items = new ItemStack[300];
        var a = new PaperPagedInventoryAccess.Source(
                new PaperPagedInventoryAccess.Shape(TYPE, KEY, "loaded", "slot:", 300),
                i -> items[i]);
        var b = new PaperPagedInventoryAccess.Source(
                new PaperPagedInventoryAccess.Shape(TYPE, KEY + "-other", "loaded", "slot:", 1),
                i -> null);
        var first = pager.scan(List.of(a, b), 1L, 3L, null, new PaperScanLimit(256));
        assertNotNull(first.next());
        var reordered = pager.scan(List.of(b, a), 1L, 3L,
                first.next(), new PaperScanLimit(256));
        assertEquals(PaperBoundedInventoryContinuation.State.INVALIDATED, reordered.state());
        var mutated = pager.scan(List.of(a, b), 1L, 4L,
                first.next(), new PaperScanLimit(256));
        assertEquals(PaperBoundedInventoryContinuation.State.INVALIDATED, mutated.state());
    }


    @Test
    void emptyContainerEvidenceProbeRemainsNegative() {
        ItemStack empty = ItemStack.of(Material.SHULKER_BOX);
        PaperTrackedItemCollector collector = new PaperTrackedItemCollector();
        assertFalse(collector.hasIdentityEvidence(empty));
        assertFalse(collector.hasNestedIdentityEvidence(empty));
    }


    private static ItemStack tracked(LoreItemIdentity identity) {
        return new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.NETHER_STAR), identity);
    }

    private static ItemStack filledShulker(int trackedSlot) {
        ItemStack root = ItemStack.of(Material.SHULKER_BOX);
        BlockStateMeta meta = assertInstanceOf(BlockStateMeta.class, root.getItemMeta());
        ShulkerBox box = assertInstanceOf(ShulkerBox.class, meta.getBlockState());
        for (int i = 0; i < 27; i++) {
            box.getInventory().setItem(
                    i, i == trackedSlot ? tracked(ID) : ItemStack.of(Material.STONE));
        }
        meta.setBlockState(box);
        assertTrue(root.setItemMeta(meta));
        return root;
    }
}
