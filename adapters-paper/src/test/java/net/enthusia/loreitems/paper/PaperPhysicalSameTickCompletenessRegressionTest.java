package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/**
 * W10 fail-closed completion contracts for Worker 07's same-tick container scan cache.
 *
 * A synthetic container deliberately has a stable, recognizable BLOCK location but
 * an unresolvable inventory snapshot. A "seen" key alone cannot prove that the
 * previous traversal finished. This is not a real cross-chunk Paper world fixture.
 */
class PaperPhysicalSameTickCompletenessRegressionTest {
    private ServerMock server;
    private PaperPhysicalTrackingListener listener;
    private Location block;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        PluginMock plugin = MockBukkit.createMockPlugin();
        World world = server.addSimpleWorld("w10-completeness");
        block = new Location(world, 15, 70, 15);
        listener = new PaperPhysicalTrackingListener(plugin,
                () -> request -> CompletableFuture.completedFuture(
                        TrackingObservationUseCase.Result.of(
                                TrackingObservationUseCase.Status.RECORDED, "recorded")),
                () -> 4, MetricsPort.noOp());
    }

    @AfterEach
    void tearDown() {
        if (listener != null) {
            listener.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void aSecondScanCannotTurnAnUnresolvedSnapshotIntoCompleteCoverage() {
        Chunk chunk = chunk(container(block, inventory(null)));

        assertFalse(scan(chunk), "A null-holder/null-location inventory cannot be captured");
        assertFalse(scan(chunk), "A repeated same-tick scan cannot reuse a failed visit as success");
    }

    @Test
    void twoIndependentChunkCallbacksCannotShareFalseCompletion() {
        // Synthetic aliasing: two chunk notifications can resolve to the same logical
        // container identity (as with the two halves of a double chest), but we
        // deliberately do not simulate actual Paper pair geometry here.
        Chunk first = chunk(container(block, inventory(null)));
        Chunk second = chunk(container(block, inventory(null)));

        assertFalse(scan(first), "First scan is incomplete");
        assertFalse(scan(second), "Second callback must inherit incomplete evidence");
    }

    @Test
    void successfullyCapturedContainerMayBeDeduplicatedInSameTick() {
        Chunk chunk = chunk(container(block, inventory(block)));

        assertTrue(scan(chunk), "A complete empty synthetic inventory is resolved");
        assertTrue(scan(chunk), "Caching a genuinely complete scan remains safe");
    }

    private boolean scan(Chunk chunk) {
        return listener.scanChunk(chunk, TrackingObservationUseCase.Presence.PRESENT,
                "w10-same-tick-coverage");
    }

    private static Chunk chunk(Container state) {
        return proxy(Chunk.class, (method, args) -> switch (method) {
            case "getEntities" -> new Entity[0];
            case "getTileEntities" -> new BlockState[] {state};
            default -> null;
        });
    }

    private static Container container(Location physical, Inventory inventory) {
        return proxy(Container.class, (method, args) -> switch (method) {
            case "getLocation" -> physical;
            case "getWorld" -> physical.getWorld();
            case "getInventory" -> inventory;
            case "hashCode" -> physical.hashCode();
            default -> null;
        });
    }

    private static Inventory inventory(Location location) {
        return proxy(Inventory.class, (method, args) -> switch (method) {
            case "getHolder" -> null;
            case "getLocation" -> location;
            case "getContents" -> new ItemStack[9];
            case "getSize" -> 9;
            default -> null;
        });
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Invocation response) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type},
                (object, method, arguments) ->
                        response.invoke(method.getName(),
                                arguments == null ? new Object[0] : arguments)));
    }
}
