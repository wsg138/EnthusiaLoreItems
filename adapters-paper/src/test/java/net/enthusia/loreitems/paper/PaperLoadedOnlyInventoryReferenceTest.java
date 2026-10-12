package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** References never force-load chunks or retain a stale inventory across ticks. */
class PaperLoadedOnlyInventoryReferenceTest {
    private static final UUID WORLD_ID = UUID.fromString(
            "11111111-2222-3333-4444-555555555555");

    @Test
    void missingChunkIsUntouchedAndLaterResolutionUsesFreshLiveInventory() {
        Inventory first = proxy(Inventory.class, (method, arguments) -> null);
        Inventory second = proxy(Inventory.class, (method, arguments) -> null);
        AtomicReference<Inventory> current = new AtomicReference<>(first);
        AtomicBoolean loaded = new AtomicBoolean(false);
        AtomicInteger blockReads = new AtomicInteger();
        Container container = proxy(Container.class, (method, arguments) ->
                "getInventory".equals(method) ? current.get() : null);
        Block block = proxy(Block.class, (method, arguments) -> {
            assertEquals("getState", method);
            assertEquals(1, arguments.length);
            assertEquals(false, arguments[0]);
            return container;
        });
        World world = proxy(World.class, (method, arguments) -> switch (method) {
            case "isChunkLoaded" -> {
                assertEquals(-2, arguments[0]);
                assertEquals(2, arguments[1]);
                yield loaded.get();
            }
            case "getBlockAt" -> {
                assertTrue(loaded.get(), "No block access while chunk is unloaded");
                blockReads.incrementAndGet();
                yield block;
            }
            default -> null;
        });
        Server server = proxy(Server.class, (method, arguments) -> world);
        Plugin plugin = proxy(Plugin.class, (method, arguments) -> server);
        PaperInventoryReference ref = new PaperInventoryReference.Block(
                WORLD_ID, -17, 64, 32);

        assertTrue(ref.resolve(plugin).isEmpty());
        assertEquals(0, blockReads.get());
        loaded.set(true);
        assertSame(first, ref.resolve(plugin).orElseThrow());
        current.set(second);
        assertSame(second, ref.resolve(plugin).orElseThrow());
        assertEquals(2, blockReads.get());
        loaded.set(false);
        assertTrue(ref.resolve(plugin).isEmpty());
        assertEquals(2, blockReads.get());
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {type},
                (proxy, method, arguments) -> invocation.invoke(
                        method.getName(), arguments == null ? new Object[0] : arguments)));
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] arguments);
    }
}
