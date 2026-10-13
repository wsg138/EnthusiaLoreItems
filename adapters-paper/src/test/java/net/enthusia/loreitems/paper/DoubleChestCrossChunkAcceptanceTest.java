package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/**
 * Interface-accurate synthetic pair fixtures, not real Paper chunk lifecycle proof.
 * The 54-slot inventory is a DoubleChestInventory with two physical 27-slot
 * Chest holders and opposite LEFT/RIGHT block data. A missing half fails closed.
 */
class DoubleChestCrossChunkAcceptanceTest {
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));

    private PaperPhysicalTrackingListener listener;
    private final List<TrackingObservationUseCase.Request> observations = new ArrayList<>();

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
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
        listener.close();
        MockBukkit.unmock();
    }

    @Test
    void sharedFiftyFourSlotInventoryHasOneCanonicalKeyAcrossPositiveXBoundary() {
        assertSharedBoundary(15, 15, BlockFace.NORTH);
    }

    @Test
    void sharedFiftyFourSlotInventoryHasOneCanonicalKeyAcrossPositiveZBoundary() {
        assertSharedBoundary(15, 15, BlockFace.EAST);
    }

    @Test
    void sharedFiftyFourSlotInventoryHasOneCanonicalKeyAcrossNegativeXBoundary() {
        assertSharedBoundary(-1, -1, BlockFace.NORTH);
    }

    @Test
    void sharedFiftyFourSlotInventoryHasOneCanonicalKeyAcrossNegativeZBoundary() {
        assertSharedBoundary(-1, -1, BlockFace.EAST);
    }

    @Test
    void sameChunkDoubleChestOnlyScansOnePhysicalInventoryPerReconciliation() {
        PairFixture fixture = new PairFixture(5, 5, BlockFace.NORTH);
        fixture.items[53] = trackedItem();
        assertTrue(scan(chunk(fixture.leftState, fixture.rightState)));
        assertEquals(1, observations.size());
        assertEquals("slot:53", observations.getFirst().location().containerPath());
        assertEquals(fixture.key(), observations.getFirst().location().locationKey());
    }

    @Test
    void movingFromFirstToLastSlotNeverCollapsesSlotIdentity() {
        PairFixture fixture = new PairFixture(15, 15, BlockFace.NORTH);
        fixture.items[0] = trackedItem();
        assertTrue(scan(chunk(fixture.leftState)));
        fixture.items[53] = fixture.items[0];
        fixture.items[0] = null;
        // A later scan is allowed to re-read; same-tick unchanged callbacks are coalesced.
        listener.drain();
        assertTrue(scan(chunk(fixture.rightState)));
        assertEquals(List.of("slot:0", "slot:53"),
                observations.stream().map(o -> o.location().containerPath()).toList());
        assertTrue(observations.stream().allMatch(o ->
                fixture.key().equals(o.location().locationKey())));
    }

    @Test
    void twoCopiesInOppositeHalvesRemainReconciliationNotAuthoritativeUnique() {
        PairFixture fixture = new PairFixture(15, 15, BlockFace.NORTH);
        fixture.items[0] = trackedItem();
        fixture.items[53] = fixture.items[0].clone();
        assertTrue(scan(chunk(fixture.leftState)));
        assertEquals(List.of("slot:0", "slot:53"),
                observations.stream().map(o -> o.location().containerPath()).toList());
        assertTrue(observations.stream().allMatch(o ->
                o.mode() == TrackingObservationUseCase.EvidenceMode.RECONCILIATION));
    }

    @Test
    void naturalChunkLifecycleInvalidatesPriorSuccessfulSameTickScan() {
        PairFixture fixture = new PairFixture(15, 15, BlockFace.NORTH);
        fixture.items[0] = trackedItem();
        Chunk loaded = fixture.chunkForLeft();
        assertTrue(scan(loaded));
        fixture.items[53] = fixture.items[0];
        fixture.items[0] = null;
        listener.onChunkLoad(new ChunkLoadEvent(loaded, false));
        assertTrue(scan(loaded));
        assertEquals(List.of("slot:0", "slot:53"),
                observations.stream().map(o -> o.location().containerPath()).toList());
    }

    @Test
    void missingHalfRejectsCompletenessWithoutReadingUnloadedNeighbor() {
        PairFixture fixture = new PairFixture(15, 15, BlockFace.NORTH);
        fixture.items[53] = trackedItem();
        fixture.unloadRight();
        assertFalse(scan(chunk(fixture.leftState)));
        assertEquals(0, fixture.unloadedReads);
        assertEquals(0, observations.size());
        assertFalse(fixture.forced);
    }

    @Test
    void unidentifiedFiftyFourSlotInventoryIsNotValidDoubleChestEvidence() {
        World world = MockBukkit.getMock().addSimpleWorld("unidentified");
        Location location = new Location(world, 15, 64, 15);
        assertFalse(scan(chunk(state(location, inventory(location, contentsAt(53))))));
        assertTrue(observations.isEmpty());
    }

    @Test
    void unseenTwoHundredFiftySeventhContainerCannotMakeChunkScanComplete() {
        World world = MockBukkit.getMock().addSimpleWorld("many");
        BlockState[] states = new BlockState[257];
        ItemStack tracked = trackedItem();
        for (int i = 0; i < states.length; i++) {
            Location location = new Location(world, i % 16, 64 + i / 256, (i / 16) % 16);
            ItemStack[] contents = new ItemStack[1];
            if (i == 0 || i == 256) contents[0] = tracked.clone();
            states[i] = state(location, inventory(location, contents));
        }
        assertFalse(scan(chunk(states)));
        assertEquals(1, observations.size());
        assertEquals(TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                observations.getFirst().mode());
    }

    private void assertSharedBoundary(int x, int z, BlockFace facing) {
        PairFixture fixture = new PairFixture(x, z, facing);
        fixture.items[53] = trackedItem();
        assertTrue(scan(chunk(fixture.leftState)));
        assertTrue(scan(chunk(fixture.rightState)));
        // One real inventory, two callbacks in the same tick: no phantom second scan.
        assertEquals(1, observations.size());
        LocationDescriptor location = observations.getFirst().location();
        assertEquals(LocationDescriptor.Type.BLOCK_CONTAINER, location.type());
        assertEquals(fixture.key(), location.locationKey());
        assertEquals("slot:53", location.containerPath());
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
        return proxy(Inventory.class, (method, args) -> switch (method) {
            case "getHolder" -> null;
            case "getLocation" -> anchor;
            case "getContents" -> contents.clone();
            case "getItem" -> contents[(int) args[0]];
            case "getSize" -> contents.length;
            default -> null;
        });
    }

    private static BlockState state(Location position, Inventory contents) {
        return proxy(Container.class, (method, args) -> switch (method) {
            case "getInventory", "getBlockInventory" -> contents;
            case "getLocation" -> position;
            case "getWorld" -> position.getWorld();
            default -> null;
        });
    }

    private static Chunk chunk(BlockState... blocks) {
        return proxy(Chunk.class, (method, args) -> switch (method) {
            case "getEntities" -> new Entity[0];
            case "getTileEntities" -> blocks.clone();
            default -> null;
        });
    }

    private static final class PairFixture {
        private final UUID id = UUID.randomUUID();
        private final int x;
        private final int z;
        private final int otherX;
        private final int otherZ;
        private final World world;
        private final Set<String> loaded = new HashSet<>();
        final ItemStack[] items = new ItemStack[54];
        final org.bukkit.block.Chest leftState;
        final org.bukkit.block.Chest rightState;
        final DoubleChestInventory combined;
        boolean forced;
        int unloadedReads;

        PairFixture(int x, int z, BlockFace facing) {
            this.x = x;
            this.z = z;
            otherX = x - facing.getModZ();
            otherZ = z + facing.getModX();
            world = proxy(World.class, this::worldMethod);
            loaded.add(chunkKey(x, z));
            loaded.add(chunkKey(otherX, otherZ));
            leftState = chest(true, facing);
            rightState = chest(false, facing);
            combined = proxy(DoubleChestInventory.class, (method, args) -> switch (method) {
                case "getSize" -> 54;
                case "getType" -> InventoryType.CHEST;
                case "getLeftSide" -> half(true);
                case "getRightSide" -> half(false);
                case "getHolder" -> {
                    assertEquals(false, args[0]);
                    yield null;
                }
                case "getLocation" -> new Location(world, (x + otherX) / 2.0,
                        64, (z + otherZ) / 2.0);
                case "getContents" -> items.clone();
                case "getItem" -> items[(int) args[0]];
                default -> null;
            });
        }

        String key() {
            boolean leftFirst = x < otherX || (x == otherX && z < otherZ);
            int ax = leftFirst ? x : otherX;
            int az = leftFirst ? z : otherZ;
            int bx = leftFirst ? otherX : x;
            int bz = leftFirst ? otherZ : z;
            return "double-chest:" + id + ":" + ax + ":64:" + az
                    + "|" + bx + ":64:" + bz + ":left=" + (leftFirst ? "0" : "1");
        }

        Chunk chunkForLeft() {
            return proxy(Chunk.class, (method, args) -> switch (method) {
                case "getWorld" -> world;
                case "getX" -> x >> 4;
                case "getZ" -> z >> 4;
                case "getEntities" -> new Entity[0];
                case "getTileEntities" -> new BlockState[] {leftState};
                default -> null;
            });
        }

        void unloadRight() {
            loaded.remove(chunkKey(otherX, otherZ));
        }

        private String chunkKey(int atX, int atZ) {
            return (atX >> 4) + ":" + (atZ >> 4);
        }

        private Object worldMethod(String method, Object[] args) {
            return switch (method) {
                case "getUID" -> id;
                case "getKey" -> NamespacedKey.minecraft("overworld");
                case "isChunkLoaded" -> loaded.contains(args[0] + ":" + args[1]);
                case "getBlockAt" -> block((int) args[0], (int) args[1], (int) args[2]);
                case "loadChunk", "getChunkAt" -> {
                    forced = true;
                    throw new AssertionError("Fixture must never force-load");
                }
                default -> null;
            };
        }

        private Block block(int bx, int by, int bz) {
            if (!loaded.contains(chunkKey(bx, bz))) {
                unloadedReads++;
                throw new AssertionError("Read of an unloaded chest half");
            }
            return proxy(Block.class, (method, args) -> {
                if (!"getState".equals(method)) return null;
                assertEquals(false, args[0]);
                if (by == 64 && bx == x && bz == z) return leftState;
                if (by == 64 && bx == otherX && bz == otherZ) return rightState;
                return null;
            });
        }

        private org.bukkit.block.Chest chest(boolean left, BlockFace facing) {
            org.bukkit.block.data.type.Chest data =
                    proxy(org.bukkit.block.data.type.Chest.class, (method, args) ->
                            switch (method) {
                                case "getType" -> left
                                        ? org.bukkit.block.data.type.Chest.Type.LEFT
                                        : org.bukkit.block.data.type.Chest.Type.RIGHT;
                                case "getFacing" -> facing;
                                default -> null;
                            });
            return proxy(org.bukkit.block.Chest.class, (method, args) ->
                    switch (method) {
                        case "getType" -> Material.CHEST;
                        case "getBlockData" -> data;
                        case "getLocation" -> new Location(world,
                                left ? x : otherX, 64, left ? z : otherZ);
                        case "getWorld" -> world;
                        case "getInventory" -> combined;
                        case "getBlockInventory" -> half(left);
                        default -> null;
                    });
        }

        private Inventory half(boolean left) {
            return proxy(Inventory.class, (method, args) -> switch (method) {
                case "getSize" -> 27;
                case "getType" -> InventoryType.CHEST;
                case "getContents" -> Arrays.copyOfRange(items, left ? 0 : 27,
                        left ? 27 : 54);
                case "getItem" -> items[(left ? 0 : 27) + (int) args[0]];
                case "getLocation" -> new Location(world,
                        left ? x : otherX, 64, left ? z : otherZ);
                case "getHolder" -> {
                    assertEquals(false, args[0]);
                    yield left ? leftState : rightState;
                }
                default -> null;
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (object, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(object);
                    case "equals" -> args != null && args.length == 1 && args[0] == object;
                    case "toString" -> "W10 Paper interface fixture";
                    default -> handler.invoke(method.getName(),
                            args == null ? new Object[0] : args);
                });
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String name, Object[] args);
    }
}
