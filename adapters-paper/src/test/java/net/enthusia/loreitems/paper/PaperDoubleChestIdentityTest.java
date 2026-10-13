package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
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
        assertEquals("double-chest:" + WORLD_ID + ":15:70:0|16:70:0", left.key());
        Inventory inventory = right.resolve(fixture.plugin).orElseThrow();
        assertEquals(54, inventory.getSize());
        assertEquals(Material.DIAMOND, inventory.getContents()[0].getType());
        assertEquals(Material.EMERALD, inventory.getContents()[53].getType());
    }

    @Test
    void actualApiLeftSideMayHaveTheHigherCoordinate() {
        Fixture fixture = new Fixture(WORLD_ID, 16, 0, BlockFace.SOUTH);
        var ref = PaperPhysicalInventorySnapshot.capture(fixture.left).orElseThrow();
        assertEquals("double-chest:" + WORLD_ID + ":15:70:0|16:70:0", ref.key());
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
        assertEquals(ref.key(), replacement.key());
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
        assertEquals("minecraft:overworld:15:70:0", single.key());
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
            return leftType == Chest.Type.LEFT ? left : right;
        }

        private Inventory apiRight() {
            return leftType == Chest.Type.LEFT ? right : left;
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
