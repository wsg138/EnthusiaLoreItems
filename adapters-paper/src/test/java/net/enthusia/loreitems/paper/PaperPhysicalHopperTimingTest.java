package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import net.enthusia.loreitems.application.TrackingMetrics;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/** Hot event telemetry must count scheduled work without changing its lifecycle. */
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
    void countsEachInventoryMoveAndItsDeferredPassExactlyOnce() {
        Inventory source = server.createInventory(null, 9);
        Inventory destination = server.createInventory(null, 9);
        InventoryMoveItemEvent event = new InventoryMoveItemEvent(
                source, ItemStack.of(Material.STONE), destination, true);

        listener.onInventoryMove(event);
        listener.onInventoryMove(event);

        assertEquals(2, metrics.snapshot().hopperMoveEvents());
        assertEquals(0, metrics.snapshot().hopperMoveDeferred());
        assertTrue(metrics.snapshot().hopperMoveEventNanos() >= 0);

        server.getScheduler().performOneTick();

        assertEquals(2, metrics.snapshot().hopperMoveDeferred());
        assertTrue(metrics.snapshot().hopperMoveDeferredNanos() >= 0);
        server.getScheduler().performOneTick();
        assertEquals(2, metrics.snapshot().hopperMoveDeferred());
    }

    @Test
    void acceptedDeferredMoveIsMeasuredDuringNormalShutdownDrain() {
        Inventory source = server.createInventory(null, 9);
        Inventory destination = server.createInventory(null, 9);
        listener.onInventoryMove(new InventoryMoveItemEvent(
                source, ItemStack.of(Material.DIRT), destination, true));

        listener.close();

        assertEquals(1, metrics.snapshot().hopperMoveEvents());
        assertEquals(1, metrics.snapshot().hopperMoveDeferred());
        server.getScheduler().performOneTick();
        assertEquals(1, metrics.snapshot().hopperMoveDeferred());
    }
}
