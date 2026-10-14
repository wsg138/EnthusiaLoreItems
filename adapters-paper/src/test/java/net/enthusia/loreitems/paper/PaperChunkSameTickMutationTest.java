package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
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
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Pure synthetic same-tick cache contract; not a real Paper double chest. */
class PaperChunkSameTickMutationTest {
    private boolean resolved;

    @Test
    void eventInvalidationPreventsPreviouslyCompleteScanFromMaskingMutation() {
        var server = MockBukkit.mock();
        try {
            var plugin = MockBukkit.createMockPlugin();
            World world = server.addSimpleWorld("mutation");
            Location location = new Location(world, 15, 64, 15);
            Inventory inventory = inventory(location);
            Container container = container(inventory, location, world);
            Chunk chunk = chunk(container);
            var coordinator = new PaperTrackingCoordinator(plugin,
                    () -> request -> CompletableFuture.completedFuture(
                            TrackingObservationUseCase.Result.of(
                                    TrackingObservationUseCase.Status.RECORDED, "recorded")),
                    () -> 1, MetricsPort.noOp());
            try {
                var items = new PaperPhysicalInventoryScanner(coordinator);
                var scanner = new PaperPhysicalChunkScanner(items,
                        new PaperPhysicalEntityScanner(items), MetricsPort.noOp(),
                        plugin.getLogger(), new PaperLoadedTrackedContainerIndex());
                resolved = true;
                assertTrue(scan(scanner, chunk));
                resolved = false;
                assertTrue(scan(scanner, chunk), "No event: completed cache is reused");
                scanner.invalidateContents(new PaperLoadedTrackedContainerIndex.BlockKey(
                        world.getUID(), 15, 64, 15));
                assertFalse(scan(scanner, chunk),
                        "Event mutation must invalidate cached complete evidence");
            } finally {
                coordinator.close();
            }
        } finally {
            MockBukkit.unmock();
        }
    }

    private Inventory inventory(Location location) {
        return proxy(Inventory.class, (method, args) -> switch (method) {
            case "getHolder" -> null;
            case "getLocation" -> resolved ? location : null;
            case "getContents" -> new ItemStack[9];
            case "getItem" -> null;
            case "getSize" -> 9;
            default -> null;
        });
    }

    private static Container container(Inventory inventory, Location location, World world) {
        return proxy(Container.class, (method, args) -> switch (method) {
            case "getInventory" -> inventory;
            case "getLocation" -> location;
            case "getWorld" -> world;
            default -> null;
        });
    }

    private static Chunk chunk(Container container) {
        return proxy(Chunk.class, (method, args) -> switch (method) {
            case "getEntities" -> new Entity[0];
            case "getTileEntities" -> new BlockState[] {container};
            default -> null;
        });
    }

    private static boolean scan(PaperPhysicalChunkScanner scanner, Chunk chunk) {
        return scanner.scanChunk(chunk, TrackingObservationUseCase.Presence.PRESENT,
                "same-tick-test", 7);
    }

    @FunctionalInterface
    private interface Answer {
        Object invoke(String method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),
                new Class<?>[] {type}, (object, method, args) ->
                        answer.invoke(method.getName(), args == null ? new Object[0] : args)));
    }
}
