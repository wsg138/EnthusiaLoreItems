package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.TrackingMetrics;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.ShulkerBox;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/** The ordinary-item fast path must retain nested and malformed evidence. */
class PaperPhysicalHopperTimingTest {
    private ServerMock server;
    private PaperPhysicalTrackingListener listener;
    private TrackingMetrics metrics;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        PluginMock plugin = MockBukkit.createMockPlugin();
        TrackingObservationUseCase recorder = request ->
                CompletableFuture.completedFuture(TrackingObservationUseCase.Result.of(
                        TrackingObservationUseCase.Status.RECORDED, "ok"));
        metrics = new TrackingMetrics();
        listener = new PaperPhysicalTrackingListener(
                plugin, () -> recorder, () -> 4, metrics);
    }

    @AfterEach
    void tearDown() {
        listener.close();
        MockBukkit.unmock();
    }

    @Test
    void thousandOrdinaryHopperMovesNeverQueueReconciliation() {
        Inventory source = inventoryReferenceProbe();
        Inventory destination = inventoryReferenceProbe();
        InventoryMoveItemEvent ordinaryMove = new InventoryMoveItemEvent(
                source, ItemStack.of(Material.STONE), destination, true);
        for (int index = 0; index < 1000; index++) {
            listener.onInventoryMove(ordinaryMove);
        }
        assertEquals(1000, metrics.snapshot().hopperMoveEvents());
        assertEquals(0, metrics.snapshot().hopperMoveDeferred());
        assertTrue(metrics.snapshot().hopperMoveEventNanos() >= 0);
        server.getScheduler().performOneTick();
        assertEquals(0, metrics.snapshot().hopperMoveDeferred());
    }

    @Test
    void trackedItemMovesRetainDeferredReconciliation() {
        InventoryMoveItemEvent move = move(trackedItem());
        listener.onInventoryMove(move);
        listener.onInventoryMove(move);
        assertEquals(2, metrics.snapshot().hopperMoveEvents());
        assertEquals(0, metrics.snapshot().hopperMoveDeferred());
        server.getScheduler().performOneTick();
        assertEquals(2, metrics.snapshot().hopperMoveDeferred());
        server.getScheduler().performOneTick();
        assertEquals(2, metrics.snapshot().hopperMoveDeferred());
    }

    @Test
    void nestedShulkerAndBundleRetainReconciliation() {
        ItemStack shulker = ItemStack.of(Material.SHULKER_BOX);
        BlockStateMeta block = (BlockStateMeta) shulker.getItemMeta();
        ShulkerBox box = (ShulkerBox) block.getBlockState();
        box.getInventory().setItem(0, trackedItem());
        block.setBlockState(box);
        assertTrue(shulker.setItemMeta(block));
        ItemStack bundle = ItemStack.of(Material.BUNDLE);
        BundleMeta meta = (BundleMeta) bundle.getItemMeta();
        meta.setItems(List.of(ItemStack.of(Material.COBBLESTONE), trackedItem()));
        assertTrue(bundle.setItemMeta(meta));
        listener.onInventoryMove(move(shulker));
        listener.onInventoryMove(move(bundle));
        server.getScheduler().performOneTick();
        assertEquals(2, metrics.snapshot().hopperMoveDeferred());
    }

    @Test
    void partialIdentityMetadataMustNotTakeOrdinaryFastPath() {
        ItemStack malformed = trackedItem();
        ItemMeta meta = malformed.getItemMeta();
        meta.getPersistentDataContainer().remove(
                NamespacedKey.fromString("enthusialoreitems:instance_id"));
        assertTrue(malformed.setItemMeta(meta));
        listener.onInventoryMove(move(malformed));
        server.getScheduler().performOneTick();
        assertEquals(1, metrics.snapshot().hopperMoveDeferred());
    }

    @Test
    void pendingTrackedMoveDrainsExactlyOnceOnClose() {
        listener.onInventoryMove(move(trackedItem()));
        listener.close();
        assertEquals(1, metrics.snapshot().hopperMoveEvents());
        assertEquals(1, metrics.snapshot().hopperMoveDeferred());
        server.getScheduler().performOneTick();
        assertEquals(1, metrics.snapshot().hopperMoveDeferred());
    }

    /** Assert Paper's no-snapshot lookup without MockBukkit's missing overload. */
    private static Inventory inventoryReferenceProbe() {
        return (Inventory) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Inventory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHolder" -> {
                        assertEquals(1, args.length);
                        assertEquals(false, args[0]);
                        yield null;
                    }
                    case "getLocation" -> null;
                    case "getContents" -> new ItemStack[9];
                    case "toString" -> "test-inventory";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private InventoryMoveItemEvent move(ItemStack item) {
        Inventory source = inventoryReferenceProbe();
        Inventory destination = inventoryReferenceProbe();
        return new InventoryMoveItemEvent(source, item, destination, true);
    }

    private static ItemStack trackedItem() {
        LoreItemIdentity identity = new LoreItemIdentity(
                new LoreDefinitionId(UUID.fromString(
                        "11111111-1111-1111-1111-111111111111")),
                new LoreInstanceId(UUID.fromString(
                        "22222222-2222-2222-2222-222222222222")),
                new TemplateRevision(1));
        return new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.NETHER_STAR), identity);
    }
}
