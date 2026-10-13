package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Chest;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.mockbukkit.mockbukkit.MockBukkit;

class PaperDoubleChestIdentityTest {
    private static final UUID WORLD_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String HOLDER_METHOD = "getHolder";
    private static final String LOCATION_METHOD = "getLocation";
    private static final String SIZE_METHOD = "getSize";
    private static final String TYPE_METHOD = "getType";

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void bothHalvesAndGuiUseSameIdentityAndExact54SlotOrdering() {
        Fixture fixture = new Fixture(WORLD_ID, 15, 0, BlockFace.NORTH);
        var left = PaperPhysicalInventorySnapshot.capture(fixture.left).orElseThrow();
        var right = PaperPhysicalInventorySnapshot.capture(fixture.right).orElseThrow();
        var gui = PaperPhysicalInventorySnapshot.capture(fixture.combined).orElseThrow();
        assertEquals(left, right);
        assertEquals(left, gui);
        assertEquals("double-chest:" + WORLD_ID + ":15:70:0|16:70:0:left=0", left.key());
        Inventory inventory = right.resolve(fixture.plugin).orElseThrow();
        assertEquals(54, inventory.getSize());
        assertEquals(Material.DIAMOND, inventory.getContents()[0].getType());
        assertEquals(Material.EMERALD, inventory.getContents()[53].getType());
    }

    @Test
    void trackedDuplicateCopiesKeepDistinctPhysicalSlotPathsAcrossBothHalves() {
        Fixture fixture = new Fixture(WORLD_ID, 15, 0, BlockFace.NORTH);
        LoreItemIdentity identity = new LoreItemIdentity(
                new LoreDefinitionId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                new LoreInstanceId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
                new TemplateRevision(1));
        ItemStack tagged = new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.NETHER_STAR), identity);
        fixture.left.getContents()[0] = tagged;
        fixture.right.getContents()[26] = tagged.clone();

        PaperPhysicalInventorySnapshot snapshot =
                PaperPhysicalInventorySnapshot.capture(fixture.combined).orElseThrow();
        Map<LoreItemIdentity, List<LocationDescriptor>> observed = new ConcurrentHashMap<>();
        new PaperTrackedItemCollector().collectArray(
                snapshot.resolve(fixture.plugin).orElseThrow().getContents(),
                snapshot.type(), snapshot.key(), "slot:", observed, new PaperScanLimit(256));

        assertEquals(List.of("slot:0", "slot:53"),
                observed.get(identity).stream().map(LocationDescriptor::containerPath).toList());
        assertEquals(snapshot.key(), observed.get(identity).get(0).locationKey());
        assertEquals(snapshot.key(), observed.get(identity).get(1).locationKey());
    }

    @Test
    void realPaperApiSidesCanBeReversedFromChestBlockDataTypes() {
        // Verified using actual Paper 26.2/Java 25: at X=8 TYPE.LEFT the
        // DoubleChestInventory API-left physical inventory was at X=9.
        // This test models that observed geometry rather than assuming that
        // Chest.Type.LEFT and getLeftSide() describe the same physical block.
        Fixture fixture = new Fixture(WORLD_ID, 15, 0, BlockFace.NORTH);
        fixture.apiSideReversed = true;
        var gui = PaperPhysicalInventorySnapshot.capture(fixture.combined).orElseThrow();
        var firstHalf = PaperPhysicalInventorySnapshot.capture(fixture.left).orElseThrow();
        var secondHalf = PaperPhysicalInventorySnapshot.capture(fixture.right).orElseThrow();
        assertEquals(gui, firstHalf);
        assertEquals(gui, secondHalf);
        assertEquals("double-chest:" + WORLD_ID + ":15:70:0|16:70:0:left=1", gui.key());
        Inventory resolved = gui.resolve(fixture.plugin).orElseThrow();
        assertEquals(Material.EMERALD, resolved.getContents()[26].getType());
        assertEquals(Material.DIAMOND, resolved.getContents()[27].getType());
    }

    @Test
    void twoRealCopiesAtHalfLocalSlotZeroHaveDifferentCanonicalPaths() {
        Fixture fixture = new Fixture(WORLD_ID, 15, 0, BlockFace.NORTH);
        LoreItemIdentity identity = new LoreItemIdentity(
                new LoreDefinitionId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                new LoreInstanceId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
                new TemplateRevision(1));
        ItemStack tagged = new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.NETHER_STAR), identity);
        fixture.left.getContents()[0] = tagged;
        fixture.right.getContents()[0] = tagged.clone();
        assertHalfZeroCopies(fixture, fixture.left, identity);
        assertHalfZeroCopies(fixture, fixture.right, identity);
        assertHalfZeroCopies(fixture, fixture.combined, identity);
    }

    private void assertHalfZeroCopies(
            Fixture fixture, Inventory source, LoreItemIdentity identity) {
        var view = PaperPhysicalInventorySnapshot.captureResolved(source, fixture.plugin)
                .orElseThrow();
        assertSame(fixture.combined, view.inventory());
        assertEquals(54, view.inventory().getSize());
        Map<LoreItemIdentity, List<LocationDescriptor>> observed = new ConcurrentHashMap<>();
        new PaperTrackedItemCollector().collectArray(
                view.inventory().getContents(), view.snapshot().type(),
                view.snapshot().key(), "slot:", observed, new PaperScanLimit(256));
        assertEquals(List.of("slot:0", "slot:27"),
                observed.get(identity).stream().map(LocationDescriptor::containerPath).toList());
    }

    @ParameterizedTest
    @CsvSource({"15,0,NORTH", "-1,0,NORTH", "-17,0,NORTH",
            "0,15,EAST", "0,-1,EAST", "0,-17,EAST",
            "16,0,SOUTH", "0,16,WEST"})
    void everyCombinedSlotHasAnExactInverseForBothPhysicalHalves(
            int x, int z, BlockFace facing) {
        Fixture fixture = new Fixture(WORLD_ID, x, z, facing);
        var pair = (PaperInventoryReference.DoubleChestBlock)
                PaperPhysicalInventorySnapshot.capture(fixture.combined).orElseThrow().reference();
        for (int full = 0; full < 54; full++) {
            var local = PaperDoubleChestIdentity.physicalSlot(pair, full).orElseThrow();
            assertEquals(full, PaperDoubleChestIdentity.combinedSlot(
                    pair, WORLD_ID, local.half(), local.localSlot()).orElseThrow());
        }
        var physicalLeft = PaperDoubleChestIdentity.Position.at(
                fixture.apiLeft().getLocation());
        var physicalRight = PaperDoubleChestIdentity.Position.at(
                fixture.apiRight().getLocation());
        assertEquals(0, PaperDoubleChestIdentity.combinedSlot(
                pair, WORLD_ID, physicalLeft, 0).orElseThrow());
        assertEquals(26, PaperDoubleChestIdentity.combinedSlot(
                pair, WORLD_ID, physicalLeft, 26).orElseThrow());
        assertEquals(27, PaperDoubleChestIdentity.combinedSlot(
                pair, WORLD_ID, physicalRight, 0).orElseThrow());
        assertEquals(53, PaperDoubleChestIdentity.combinedSlot(
                pair, WORLD_ID, physicalRight, 26).orElseThrow());
        assertTrue(PaperDoubleChestIdentity.combinedSlot(
                pair, UUID.randomUUID(), physicalLeft, 0).isEmpty());
        assertTrue(PaperDoubleChestIdentity.combinedSlot(
                pair, WORLD_ID, physicalLeft, 27).isEmpty());
        assertTrue(PaperDoubleChestIdentity.physicalSlot(pair, 54).isEmpty());
    }

    @Test
    void inaccessibleSecondChunkCannotProduceCanonicalScanView() {
        Fixture fixture = new Fixture(WORLD_ID, 15, 0, BlockFace.NORTH);
        fixture.unload(fixture.partnerX, fixture.partnerZ);
        fixture.reads = 0;
        assertTrue(PaperPhysicalInventorySnapshot.captureResolved(
                fixture.left, fixture.plugin).isEmpty());
        assertEquals(0, fixture.reads, "No block reads when one half is inaccessible");
        assertFalse(fixture.forced);
    }

    @Test
    void actualApiLeftSideMayHaveTheHigherCoordinate() {
        Fixture fixture = new Fixture(WORLD_ID, 16, 0, BlockFace.SOUTH);
        var ref = PaperPhysicalInventorySnapshot.capture(fixture.left).orElseThrow();
        assertEquals("double-chest:" + WORLD_ID + ":15:70:0|16:70:0:left=1", ref.key());
        assertEquals(Material.DIAMOND, ref.resolve(fixture.plugin)
                .orElseThrow().getContents()[0].getType());
    }

    @ParameterizedTest
    @CsvSource({
        "15,0,NORTH", "-1,0,NORTH", "-17,0,NORTH",
        "0,15,EAST", "0,-1,EAST", "0,-17,EAST", "0,16,WEST"
    })
    void crossingPositiveAndNegativeXAndZChunkBoundariesIsStable(
            int x, int z, BlockFace facing) {
        Fixture f = new Fixture(WORLD_ID, x, z, facing);
        assertEquals(PaperPhysicalInventorySnapshot.capture(f.left).orElseThrow(),
                PaperPhysicalInventorySnapshot.capture(f.right).orElseThrow());
        assertEquals(54, PaperPhysicalInventorySnapshot.capture(f.combined)
                .orElseThrow().resolve(f.plugin).orElseThrow().getSize());
    }

    @Test
    void missingOtherHalfNeverReadsUnloadedChunkOrForceLoads() {
        Fixture fixture = new Fixture(WORLD_ID, 15, 0, BlockFace.NORTH);
        var ref = PaperPhysicalInventorySnapshot.capture(fixture.combined).orElseThrow();
        fixture.unload(fixture.partnerX, fixture.partnerZ);
        fixture.reads = 0;
        assertTrue(ref.resolve(fixture.plugin).isEmpty());
        assertTrue(PaperPhysicalInventorySnapshot.capture(fixture.combined).isEmpty());
        assertEquals(0, fixture.reads);
        assertTrue(PaperPhysicalInventorySnapshot.capture(fixture.left).isEmpty());
        assertEquals(0, fixture.reads);
        assertFalse(fixture.forced);
        fixture.load(fixture.partnerX, fixture.partnerZ);
        assertSame(fixture.combined, ref.resolve(fixture.plugin).orElseThrow());
    }

    @Test
    void splitFacingChangeBreakAndPartial54ViewFailClosed() {
        Fixture fixture = new Fixture(WORLD_ID, 15, 0, BlockFace.NORTH);
        var ref = PaperPhysicalInventorySnapshot.capture(fixture.left).orElseThrow();
        fixture.rightType = Chest.Type.SINGLE;
        assertTrue(ref.resolve(fixture.plugin).isEmpty());
        fixture.rightType = Chest.Type.RIGHT;
        fixture.rightFacing = BlockFace.SOUTH;
        assertTrue(ref.resolve(fixture.plugin).isEmpty());
        fixture.rightFacing = BlockFace.NORTH;
        fixture.broken = true;
        assertTrue(ref.resolve(fixture.plugin).isEmpty());
        fixture.broken = false;
        fixture.size = 27;
        assertTrue(ref.resolve(fixture.plugin).isEmpty());
        fixture.size = 54;
        fixture.leftType = Chest.Type.RIGHT;
        fixture.rightType = Chest.Type.LEFT;
        fixture.leftFacing = BlockFace.SOUTH;
        fixture.rightFacing = BlockFace.SOUTH;
        assertTrue(ref.resolve(fixture.plugin).isEmpty(),
                "An old reference must not reinterpret swapped 0..53 slots");
        var replacement = PaperPhysicalInventorySnapshot.capture(fixture.combined).orElseThrow();
        assertNotEquals(ref.key(), replacement.key(),
                "Reoriented chest must not reuse persisted slot identities");
        assertNotEquals(ref.reference(), replacement.reference());
        assertEquals(Material.EMERALD,
                replacement.resolve(fixture.plugin).orElseThrow().getContents()[26].getType());
    }

    @Test
    void singleChestIsNotSilentlyReinterpretedAfterMerge() {
        Fixture fixture = new Fixture(WORLD_ID, 15, 0, BlockFace.NORTH);
        fixture.leftType = Chest.Type.SINGLE;
        fixture.rightType = Chest.Type.SINGLE;
        var single = PaperPhysicalInventorySnapshot.capture(fixture.left).orElseThrow();
        assertEquals("block-v2:" + WORLD_ID + ":15:70:0", single.key());
        assertSame(fixture.left, single.resolve(fixture.plugin).orElseThrow());
        fixture.leftType = Chest.Type.LEFT;
        fixture.rightType = Chest.Type.RIGHT;
        assertTrue(single.resolve(fixture.plugin).isEmpty());
    }

    @Test
    void equalCoordinatesInDifferentWorldsNeverShareIdentity() {
        Fixture a = new Fixture(WORLD_ID, 15, 0, BlockFace.NORTH);
        Fixture b = new Fixture(UUID.randomUUID(), 15, 0, BlockFace.NORTH);
        var ar = PaperPhysicalInventorySnapshot.capture(a.combined).orElseThrow();
        var br = PaperPhysicalInventorySnapshot.capture(b.combined).orElseThrow();
        assertNotEquals(ar.key(), br.key());
        assertTrue(ar.resolve(b.plugin).isEmpty());
    }

    @Test
    void ambiguousNullHolderHalfCannotBeSavedAsSingleChest() {
        Fixture f = new Fixture(WORLD_ID, 15, 0, BlockFace.NORTH);
        Inventory unknown = proxy(Inventory.class, (method, args) -> switch (method) {
            case HOLDER_METHOD -> {
                assertEquals(false, args[0]);
                yield null;
            }
            case LOCATION_METHOD -> new Location(f.world, 15, 70, 0);
            case SIZE_METHOD -> 27;
            default -> null;
        });
        assertTrue(PaperPhysicalInventorySnapshot.capture(unknown).isEmpty());
    }

    @Test
    void mismatchedHalfHolderCannotLaunderItsSlotsIntoOtherHalf() {
        Fixture f = new Fixture(WORLD_ID, 15, 0, BlockFace.NORTH);
        Inventory mismatch = proxy(Inventory.class, (method, args) -> switch (method) {
            case HOLDER_METHOD -> f.state(true);
            case LOCATION_METHOD -> new Location(f.world, f.partnerX, 70, f.partnerZ);
            case SIZE_METHOD -> 27;
            default -> null;
        });
        assertTrue(PaperPhysicalInventorySnapshot.capture(mismatch).isEmpty());
    }

    @Test
    void worldSideInventoryAndOwnerSideFallbackUseSameSlotIdentity() {
        Fixture f = new Fixture(WORLD_ID, 15, 0, BlockFace.NORTH);
        org.bukkit.block.DoubleChest owner = new org.bukkit.block.DoubleChest(f.combined);
        Inventory unknownSide = proxy(Inventory.class, (method, args) -> null);
        DoubleChestInventory view = proxy(DoubleChestInventory.class, (method, args) ->
                switch (method) {
                    case SIZE_METHOD -> 54;
                    case "getLeftSide", "getRightSide" -> unknownSide;
                    case HOLDER_METHOD -> {
                        assertEquals(false, args[0]);
                        yield owner;
                    }
                    default -> null;
                });
        assertEquals(PaperPhysicalInventorySnapshot.capture(f.combined).orElseThrow(),
                PaperPhysicalInventorySnapshot.capture(view).orElseThrow());
    }

    private static final class Fixture {
        final UUID id;
        final World world;
        final Plugin plugin;
        final int x;
        final int z;
        final int partnerX;
        final int partnerZ;
        final Inventory left;
        final Inventory right;
        final DoubleChestInventory combined;
        final Set<String> chunks = new HashSet<>();
        boolean apiSideReversed;
        Chest.Type leftType = Chest.Type.LEFT;
        Chest.Type rightType = Chest.Type.RIGHT;
        BlockFace leftFacing;
        BlockFace rightFacing;
        boolean broken;
        boolean forced;
        int reads;
        int size = 54;

        Fixture(UUID id, int x, int z, BlockFace facing) {
            this.id = id;
            this.x = x;
            this.z = z;
            partnerX = x - facing.getModZ();
            partnerZ = z + facing.getModX();
            leftFacing = facing;
            rightFacing = facing;
            world = proxy(World.class, this::worldCall);
            load(x, z);
            load(partnerX, partnerZ);
            left = half(x, z, Material.DIAMOND, true);
            right = half(partnerX, partnerZ, Material.EMERALD, false);
            combined = proxy(DoubleChestInventory.class, this::doubleCall);
            Server server = proxy(Server.class, (method, args) ->
                    "getWorld".equals(method) && id.equals(args[0]) ? world : null);
            plugin = proxy(Plugin.class, (method, args) ->
                    "getServer".equals(method) ? server : null);
        }

        private Object worldCall(String method, Object[] args) {
            return switch (method) {
                case "getUID" -> id;
                case "getKey" -> NamespacedKey.minecraft("overworld");
                case "isChunkLoaded" -> chunks.contains(args[0] + ":" + args[1]);
                case "getBlockAt" -> block((int) args[0], (int) args[1], (int) args[2]);
                case "loadChunk", "getChunkAt" -> {
                    forced = true;
                    throw new AssertionError("Forced chunk loading");
                }
                default -> null;
            };
        }

        private Object doubleCall(String method, Object[] args) {
            return switch (method) {
                case SIZE_METHOD -> size;
                case TYPE_METHOD -> InventoryType.CHEST;
                case "getLeftSide" -> apiLeft();
                case "getRightSide" -> apiRight();
                case HOLDER_METHOD -> holderWithoutSnapshot(args);
                case LOCATION_METHOD -> new Location(world, (x + partnerX) / 2.0,
                        70, (z + partnerZ) / 2.0);
                case "getContents" -> combinedContents();
                default -> null;
            };
        }

        private Object holderWithoutSnapshot(Object[] args) {
            assertEquals(false, args[0]);
            return null;
        }

        private Inventory apiLeft() {
            boolean firstLeft = leftType == Chest.Type.LEFT;
            return firstLeft == apiSideReversed ? right : left;
        }

        private Inventory apiRight() {
            boolean firstLeft = leftType == Chest.Type.LEFT;
            return firstLeft == apiSideReversed ? left : right;
        }

        private ItemStack[] combinedContents() {
            ItemStack[] items = new ItemStack[54];
            System.arraycopy(apiLeft().getContents(), 0, items, 0, 27);
            System.arraycopy(apiRight().getContents(), 0, items, 27, 27);
            return items;
        }

        void load(int x, int z) {
            chunks.add((x >> 4) + ":" + (z >> 4));
        }

        void unload(int x, int z) {
            chunks.remove((x >> 4) + ":" + (z >> 4));
        }

        private Object block(int atX, int atY, int atZ) {
            if (!chunks.contains((atX >> 4) + ":" + (atZ >> 4))) {
                throw new AssertionError("Read unloaded chunk");
            }
            reads++;
            return proxy(Block.class, (method, args) ->
                    "getState".equals(method) ? checkedState(atX, atY, atZ, args) : null);
        }

        private Object checkedState(int atX, int atY, int atZ, Object[] args) {
            assertEquals(false, args[0], "Never request block state snapshot");
            boolean isRight = atX == partnerX && atZ == partnerZ;
            if (broken && isRight) {
                return null;
            }
            boolean isLeft = atX == x && atZ == z;
            return (isLeft || isRight) && atY == 70 ? state(isLeft) : null;
        }

        private org.bukkit.block.Chest state(boolean first) {
            Chest data = proxy(Chest.class, (method, args) -> switch (method) {
                case TYPE_METHOD -> first ? leftType : rightType;
                case "getFacing" -> first ? leftFacing : rightFacing;
                default -> null;
            });
            return proxy(org.bukkit.block.Chest.class,
                    (method, args) -> chestCall(first, data, method));
        }

        private Object chestCall(boolean first, Chest data, String method) {
            return switch (method) {
                case TYPE_METHOD -> Material.CHEST;
                case "getBlockData" -> data;
                case LOCATION_METHOD -> stateLocation(first);
                case "getInventory" -> stateInventory(first);
                default -> null;
            };
        }

        private Location stateLocation(boolean first) {
            return new Location(world, first ? x : partnerX, 70, first ? z : partnerZ);
        }

        private Inventory stateInventory(boolean first) {
            Chest.Type type = first ? leftType : rightType;
            if (type == Chest.Type.SINGLE) {
                return first ? left : right;
            }
            return combined;
        }

        private Inventory half(int x, int z, Material item, boolean first) {
            ItemStack[] items = new ItemStack[27];
            items[first ? 0 : 26] = new ItemStack(item);
            return proxy(Inventory.class, (method, args) -> switch (method) {
                case SIZE_METHOD -> 27;
                case TYPE_METHOD -> InventoryType.CHEST;
                case LOCATION_METHOD -> new Location(world, x, 70, z);
                case HOLDER_METHOD -> {
                    assertEquals(false, args[0]);
                    yield state(first);
                }
                case "getContents" -> items;
                default -> null;
            });
        }
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), new Class<?>[] {type},
                (proxy, method, arguments) -> invocation.invoke(
                        method.getName(), arguments == null ? new Object[0] : arguments)));
    }
}
