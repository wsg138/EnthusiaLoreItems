package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Chest;
import org.junit.jupiter.api.Test;

/** Guard geometry and access order without ever loading any chunk. */
class PaperDoubleChestAvailabilityGuardTest {
    @Test
    void allFacingsAndBothHalvesIdentifyThePhysicalPartner() {
        var guard = PaperDoubleChestAvailabilityGuard.class;
        assertEquals(new PaperDoubleChestAvailabilityGuard.Half(16, 0),
                PaperDoubleChestAvailabilityGuard.partner(15, 0, BlockFace.NORTH,
                        Chest.Type.LEFT));
        assertEquals(new PaperDoubleChestAvailabilityGuard.Half(-17, 0),
                PaperDoubleChestAvailabilityGuard.partner(-16, 0, BlockFace.NORTH,
                        Chest.Type.RIGHT));
        assertEquals(new PaperDoubleChestAvailabilityGuard.Half(0, 16),
                PaperDoubleChestAvailabilityGuard.partner(0, 15, BlockFace.EAST,
                        Chest.Type.LEFT));
        assertEquals(new PaperDoubleChestAvailabilityGuard.Half(0, -17),
                PaperDoubleChestAvailabilityGuard.partner(0, -16, BlockFace.EAST,
                        Chest.Type.RIGHT));
        assertEquals(new PaperDoubleChestAvailabilityGuard.Half(-1, 0),
                PaperDoubleChestAvailabilityGuard.partner(0, 0, BlockFace.SOUTH,
                        Chest.Type.LEFT));
        assertEquals(new PaperDoubleChestAvailabilityGuard.Half(1, 0),
                PaperDoubleChestAvailabilityGuard.partner(0, 0, BlockFace.SOUTH,
                        Chest.Type.RIGHT));
        assertEquals(new PaperDoubleChestAvailabilityGuard.Half(0, -1),
                PaperDoubleChestAvailabilityGuard.partner(0, 0, BlockFace.WEST,
                        Chest.Type.LEFT));
        assertEquals(new PaperDoubleChestAvailabilityGuard.Half(0, 1),
                PaperDoubleChestAvailabilityGuard.partner(0, 0, BlockFace.WEST,
                        Chest.Type.RIGHT));
        assertNull(PaperDoubleChestAvailabilityGuard.partner(
                0, 0, BlockFace.NORTH, Chest.Type.SINGLE));
        assertNull(PaperDoubleChestAvailabilityGuard.partner(
                0, 0, BlockFace.UP, Chest.Type.LEFT));
    }

    @Test
    void unloadedPartnerChunkIsRejectedBeforeAnyNeighborBlockAccess() {
        Chest originalData = chest(Chest.Type.LEFT, BlockFace.NORTH);
        Block original = block(15, 64, 0, originalData);
        AtomicInteger checked = new AtomicInteger();
        World world = proxy(World.class, (method, args) -> switch (method) {
            case "isChunkLoaded" -> {
                assertEquals(1, args[0]);
                assertEquals(0, args[1]);
                checked.incrementAndGet();
                yield false;
            }
            case "getBlockAt" -> throw new AssertionError(
                    "An unloaded partner's block must not be read or force-loaded");
            default -> throw new UnsupportedOperationException(method);
        });

        assertFalse(PaperDoubleChestAvailabilityGuard.isAccessible(world, original));
        assertEquals(1, checked.get());
    }

    @Test
    void bothLoadedHalvesMustHaveOppositeTypesAndTheSameFacing() {
        Chest first = chest(Chest.Type.LEFT, BlockFace.NORTH);
        AtomicReference<Chest.Type> otherType = new AtomicReference<>(Chest.Type.RIGHT);
        AtomicReference<BlockFace> otherFacing = new AtomicReference<>(BlockFace.NORTH);
        Chest second = proxy(Chest.class, (method, args) -> switch (method) {
            case "getType" -> otherType.get();
            case "getFacing" -> otherFacing.get();
            default -> throw new UnsupportedOperationException(method);
        });
        Block original = block(15, 64, 0, first);
        Block neighbor = block(16, 64, 0, second);
        World world = proxy(World.class, (method, args) -> switch (method) {
            case "isChunkLoaded" -> true;
            case "getBlockAt" -> {
                assertEquals(16, args[0]);
                assertEquals(64, args[1]);
                assertEquals(0, args[2]);
                yield neighbor;
            }
            default -> throw new UnsupportedOperationException(method);
        });

        assertTrue(PaperDoubleChestAvailabilityGuard.isAccessible(world, original));
        otherType.set(Chest.Type.LEFT);
        assertFalse(PaperDoubleChestAvailabilityGuard.isAccessible(world, original));
        otherType.set(Chest.Type.RIGHT);
        otherFacing.set(BlockFace.EAST);
        assertFalse(PaperDoubleChestAvailabilityGuard.isAccessible(world, original));
    }

    private static Chest chest(Chest.Type type, BlockFace facing) {
        return proxy(Chest.class, (method, args) -> switch (method) {
            case "getType" -> type;
            case "getFacing" -> facing;
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private static Block block(int x, int y, int z, Chest data) {
        return proxy(Block.class, (method, args) -> switch (method) {
            case "getX" -> x;
            case "getY" -> y;
            case "getZ" -> z;
            case "getType" -> Material.CHEST;
            case "getBlockData" -> data;
            default -> throw new UnsupportedOperationException(method);
        });
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), new Class<?>[] {type},
                (ignored, method, raw) -> invocation.invoke(
                        method.getName(), raw == null ? new Object[0] : raw)));
    }
}
