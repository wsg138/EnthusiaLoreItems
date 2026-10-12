package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/**
 * Synthetic, deliberately narrow double-chest regressions. These do NOT construct a real
 * Paper double chest, unload chunks, or certify behavior at a live chunk boundary.
 * The real-Paper gate is documented in docs/performance/2026-10-12-cross-chunk-double-chest-gate.md.
 */
class DoubleChestCrossChunkAcceptanceTest {
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));

    private ServerMock server;
    private PaperPhysicalTrackingListener listener;
    private final List<TrackingObservationUseCase.Request> observations = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        PluginMock plugin = MockBukkit.createMockPlugin();
        TrackingObservationUseCase recording = request -> {
            observations.add(request);
            return CompletableFuture.completedFuture(TrackingObservationUseCase.Result.of(
                    TrackingObservationUseCase.Status.RECORDED, "recorded"));
        };
        listener = new PaperPhysicalTrackingListener(
                plugin, () -> recording, () -> 4, MetricsPort.noOp());
    }

    @AfterEach
    void tearDown() {
        if (listener != null) {
            listener.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void sharedFiftyFourSlotInventoryHasOneCanonicalKeyAcrossPositiveXBoundary() {
        assertSharedBoundary(15, 15, 16, 15);
    }

    @Test
    void sharedFiftyFourSlotInventoryHasOneCanonicalKeyAcrossPositiveZBoundary() {
        assertSharedBoundary(15, 15, 15, 16);
    }

    @Test
    void sharedFiftyFourSlotInventoryHasOneCanonicalKeyAcrossNegativeXBoundary() {
        assertSharedBoundary(-1, -1, 0, -1);
    }

    @Test
    void sharedFiftyFourSlotInventoryHasOneCanonicalKeyAcrossNegativeZBoundary() {
        assertSharedBoundary(-1, -1, -1, 0);
    }

    @Test
    void sameChunkDoubleChestOnlyScansOnePhysicalInventoryPerReconciliation() {
        World world = server.addSimpleWorld("acceptance");
        Location first = new Location(world, 5, 64, 5);
        Location second = new Location(world, 6, 64, 5);
        ItemStack[] contents = contentsAt(53);
        Inventory shared = inventory(first, contents);

        assertTrue(scan(chunk(state(first, shared), state(second, shared))));
        assertEquals(1, observations.size());
        assertEquals("slot:53", observations.getFirst().location().containerPath());
    }

    @Test
    void movingFromFirstToLastSlotNeverCollapsesSlotIdentity() {
        World world = server.addSimpleWorld("acceptance");
        Location first = new Location(world, 15, 64, 15);
        Location second = new Location(world, 16, 64, 15);
        ItemStack[] contents = contentsAt(0);
        Inventory shared = inventory(first, contents);

        assertTrue(scan(chunk(state(first, shared))));
        contents[53] = contents[0];
        contents[0] = null;
        assertTrue(scan(chunk(state(second, shared))));

        assertEquals(List.of("slot:0", "slot:53"),
                observations.stream().map(o -> o.location().containerPath()).toList());
        assertEquals(observations.get(0).location().locationKey(),
                observations.get(1).location().locationKey());
    }

    @Test
    void twoCopiesInOppositeHalvesRemainReconciliationNotAuthoritativeUnique() {
        World world = server.addSimpleWorld("acceptance");
        Location first = new Location(world, 15, 64, 15);
        Location second = new Location(world, 16, 64, 15);
        ItemStack[] contents = contentsAt(0);
        contents[53] = contents[0].clone();
        Inventory shared = inventory(first, contents);

        assertTrue(scan(chunk(state(first, shared))));
        assertEquals(List.of("slot:0", "slot:53"),
                observations.stream().map(o -> o.location().containerPath()).toList());
        assertTrue(observations.stream().allMatch(o ->
                o.mode() == TrackingObservationUseCase.EvidenceMode.RECONCILIATION));
    }

    @Test
    void unseenTwoHundredFiftySeventhContainerCannotMakeChunkScanComplete() {
        World world = server.addSimpleWorld("acceptance");
        BlockState[] states = new BlockState[257];
        ItemStack tracked = trackedItem();
        for (int i = 0; i < states.length; i++) {
            Location location = new Location(world, i % 16, 64 + i / 256, (i / 16) % 16);
            ItemStack[] contents = new ItemStack[1];
            if (i == 0 || i == 256) {
                contents[0] = tracked.clone();
            }
            states[i] = state(location, inventory(location, contents));
        }

        assertFalse(scan(chunk(states)));
        assertEquals(1, observations.size());
        assertEquals(TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                observations.getFirst().mode());
    }

    private void assertSharedBoundary(int ax, int az, int bx, int bz) {
        World world = server.addSimpleWorld("acceptance");
        Location first = new Location(world, ax, 64, az);
        Location second = new Location(world, bx, 64, bz);
        Inventory shared = inventory(first, contentsAt(53));

        assertTrue(scan(chunk(state(first, shared))));
        assertTrue(scan(chunk(state(second, shared))));
        String physical = world.getKey() + ":" + ax + ":64:" + az;
        assertEquals(2, observations.size());
        assertTrue(observations.stream().allMatch(o ->
                o.location().type() == LocationDescriptor.Type.BLOCK_CONTAINER
                        && physical.equals(o.location().locationKey())
                        && "slot:53".equals(o.location().containerPath())));
    }

    private boolean scan(Chunk chunk) {
        return listener.scanChunk(
                chunk, TrackingObservationUseCase.Presence.PRESENT, "w10-synthetic");
    }

    private static ItemStack[] contentsAt(int slot) {
        ItemStack[] contents = new ItemStack[54];
        contents[slot] = trackedItem();
        return contents;
    }

    private static ItemStack trackedItem() {
        return new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.NETHER_STAR), IDENTITY);
    }

    private static Inventory inventory(Location anchor, ItemStack[] contents) {
        return proxy(Inventory.class, (object, method, args) -> switch (method) {
            case "getHolder" -> null;
            case "getLocation" -> anchor;
            case "getContents" -> contents.clone();
            case "getSize" -> contents.length;
            default -> objectMethod(object, method, args);
        });
    }

    private static BlockState state(Location position, Inventory contents) {
        return proxy(Container.class, (object, method, args) -> switch (method) {
            case "getInventory", "getBlockInventory" -> contents;
            case "getLocation" -> position;
            case "getWorld" -> position.getWorld();
            default -> objectMethod(object, method, args);
        });
    }

    private static Chunk chunk(BlockState... blocks) {
        return proxy(Chunk.class, (object, method, args) -> switch (method) {
            case "getEntities" -> new Entity[0];
            case "getTileEntities" -> blocks.clone();
            default -> objectMethod(object, method, args);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (object, method, args) -> handler.invoke(
                        object, method.getName(), args == null ? new Object[0] : args));
    }

    private static Object objectMethod(Object object, String method, Object[] args) {
        return switch (method) {
            case "hashCode" -> System.identityHashCode(object);
            case "equals" -> args.length == 1 && args[0] == object;
            case "toString" -> "W10 synthetic Paper proxy";
            default -> throw new UnsupportedOperationException(method);
        };
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(Object object, String name, Object[] args);
    }
}
