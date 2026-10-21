package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/** Preserve bounded container scans while avoiding redundant inventory retrieval. */
class PaperChunkContainerSingleInventoryReadTest {
    private static final String INVENTORY_METHOD = "getInventory";
    private ServerMock server;
    private PaperPhysicalTrackingListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        PluginMock plugin = MockBukkit.createMockPlugin();
        TrackingObservationUseCase useCase = request ->
                CompletableFuture.completedFuture(TrackingObservationUseCase.Result.of(
                        TrackingObservationUseCase.Status.RECORDED, "ok"));
        listener = new PaperPhysicalTrackingListener(
                plugin, () -> useCase, () -> 4, MetricsPort.noOp());
    }

    @AfterEach
    void tearDown() {
        listener.close();
        MockBukkit.unmock();
    }

    @Test
    void scanUsesSameInventoryForLocationCaptureAndContents() {
        World world = server.addSimpleWorld("world");
        Inventory delegate = server.createInventory(null, 9);
        AtomicInteger reads = new AtomicInteger();
        Location location = new Location(world, 12, 70, -8);
        InventoryHolder[] holder = new InventoryHolder[1];
        Inventory inventory = (Inventory) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), new Class<?>[] {Inventory.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getHolder" -> holder[0];
                    case "getLocation" -> location;
                    default -> invoke(method, delegate, arguments);
                });
        holder[0] = (InventoryHolder) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {BlockState.class, InventoryHolder.class},
                (proxy, method, arguments) -> {
                    if (INVENTORY_METHOD.equals(method.getName())) {
                        reads.incrementAndGet();
                        return inventory;
                    }
                    return null;
                });
        Chunk chunk = (Chunk) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), new Class<?>[] {Chunk.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getTileEntities" -> {
                        assertEquals(1, arguments.length, "Chunk must not capture full block-state snapshots");
                        assertEquals(false, arguments[0], "Use Paper live block-state references");
                        yield new BlockState[] {(BlockState) holder[0]};
                    }
                    case "getEntities" -> new Entity[0];
                    default -> null;
                });

        listener.scanChunk(
                chunk, TrackingObservationUseCase.Presence.PRESENT, "test-chunk");

        assertEquals(1, reads.get(), "Read each container inventory once per chunk scan");
    }

    private static Object invoke(
            java.lang.reflect.Method method, Object delegate, Object[] arguments)
            throws Throwable {
        try {
            return method.invoke(delegate, arguments);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }
}
