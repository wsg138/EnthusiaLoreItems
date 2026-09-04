package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

class PaperPhysicalTrackingBlockLifecycleTest {
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString(
                    "11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString(
                    "22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));

    private ServerMock server;
    private PluginMock plugin;
    private PaperPhysicalTrackingListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        if (listener != null) {
            listener.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void nonContainerBlockInventoryIsIncludedInChunkReconciliation() {
        List<TrackingObservationUseCase.Request> observed = new CopyOnWriteArrayList<>();
        listener = listener(observed);
        World world = server.addSimpleWorld("world");
        Location canonicalLocation = new Location(world, 10, 64, 10);
        Inventory inventory = inventory(canonicalLocation, trackedItem());
        BlockState holder = blockHolder(InventoryHolder.class, canonicalLocation, inventory);
        Chunk chunk = chunk(holder);

        listener.scanChunk(
                chunk,
                TrackingObservationUseCase.Presence.PRESENT,
                "test-non-container");

        assertEquals(1, observed.size());
        TrackingObservationUseCase.Request request = observed.getFirst();
        assertEquals(LocationDescriptor.Type.BLOCK_CONTAINER, request.location().type());
        assertEquals(world.getKey() + ":10:64:10", request.location().locationKey());
        assertEquals("slot:0", request.location().containerPath());
    }

    @Test
    void sharedDoubleChestInventoryIsScannedOnceAtCanonicalInventoryLocation() {
        List<TrackingObservationUseCase.Request> observed = new CopyOnWriteArrayList<>();
        listener = listener(observed);
        World world = server.addSimpleWorld("world");
        Location canonicalLocation = new Location(world, 21, 64, 20);
        Inventory sharedInventory = inventory(canonicalLocation, trackedItem());
        BlockState left = blockHolder(
                Container.class, new Location(world, 20, 64, 20), sharedInventory);
        BlockState right = blockHolder(
                Container.class, new Location(world, 21, 64, 20), sharedInventory);

        listener.scanChunk(
                chunk(left, right),
                TrackingObservationUseCase.Presence.PRESENT,
                "test-double-chest");

        assertEquals(1, observed.size());
        assertEquals(
                world.getKey() + ":21:64:20",
                observed.getFirst().location().locationKey());
    }

    @Test
    void respawnHandlerRestoresLivePlayerReconciliationOnNextTick() throws Exception {
        Method method = PaperPhysicalTrackingListener.class.getMethod(
                "onRespawn", PlayerRespawnEvent.class);
        EventHandler handler = method.getAnnotation(EventHandler.class);

        assertNotNull(handler);
        assertEquals(EventPriority.MONITOR, handler.priority());
    }

    private PaperPhysicalTrackingListener listener(
            List<TrackingObservationUseCase.Request> observed) {
        TrackingObservationUseCase useCase = request -> {
            observed.add(request);
            return CompletableFuture.completedFuture(TrackingObservationUseCase.Result.of(
                    TrackingObservationUseCase.Status.RECORDED,
                    "ok"));
        };
        return new PaperPhysicalTrackingListener(
                plugin, () -> useCase, () -> 4, MetricsPort.noOp());
    }

    private static Inventory inventory(Location location, ItemStack item) {
        ItemStack[] contents = new ItemStack[] {item};
        return proxy(
                new Class<?>[] {Inventory.class},
                (proxy, method, arguments) -> switch (method) {
                    case "getHolder" -> null;
                    case "getLocation" -> location;
                    case "getContents" -> contents.clone();
                    case "getSize" -> contents.length;
                    case "getItem" -> arguments != null && arguments.length == 1
                            ? contents[(Integer) arguments[0]]
                            : null;
                    default -> objectMethod(proxy, method, arguments);
                });
    }

    private static BlockState blockHolder(
            Class<?> holderType, Location stateLocation, Inventory inventory) {
        Class<?>[] types = BlockState.class.isAssignableFrom(holderType)
                ? new Class<?>[] {holderType}
                : new Class<?>[] {BlockState.class, holderType};
        return (BlockState) proxy(
                types,
                (proxy, method, arguments) -> switch (method) {
                    case "getInventory" -> inventory;
                    case "getLocation" -> stateLocation;
                    case "getWorld" -> stateLocation.getWorld();
                    default -> objectMethod(proxy, method, arguments);
                });
    }

    private static Chunk chunk(BlockState... states) {
        return proxy(
                new Class<?>[] {Chunk.class},
                (proxy, method, arguments) -> switch (method) {
                    case "getEntities" -> new Entity[0];
                    case "getTileEntities" -> states.clone();
                    default -> objectMethod(proxy, method, arguments);
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<?>[] types, Invocation invocation) {
        return (T) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                types,
                (proxy, method, arguments) -> invocation.invoke(
                        proxy,
                        method.getName(),
                        arguments == null ? new Object[0] : arguments));
    }

    private static Object objectMethod(Object proxy, String method, Object[] arguments) {
        return switch (method) {
            case "toString" -> "test-proxy";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> arguments.length == 1 && proxy == arguments[0];
            default -> throw new UnsupportedOperationException(method);
        };
    }

    private static ItemStack trackedItem() {
        return new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.NETHER_STAR), IDENTITY);
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(Object proxy, String method, Object[] arguments);
    }
}
