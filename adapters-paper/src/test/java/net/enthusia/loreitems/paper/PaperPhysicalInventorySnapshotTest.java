package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.Test;

class PaperPhysicalInventorySnapshotTest {
    private static final String HOLDER_METHOD = "getHolder";
    @Test
    void entityInventoryUsesStableEntityIdentityBeforeBlockLocation() {
        UUID entityId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        World world = proxy(World.class, (method, arguments) -> switch (method) {
            case "getKey" -> NamespacedKey.minecraft("overworld");
            default -> objectMethod(method, arguments);
        });
        AtomicReference<Inventory> inventoryReference = new AtomicReference<>();
        InventoryHolder holder = proxy(
                new Class<?>[] {Entity.class, InventoryHolder.class},
                (method, arguments) -> switch (method) {
                    case "getUniqueId" -> entityId;
                    case "getWorld" -> world;
                    case "getInventory" -> inventoryReference.get();
                    default -> objectMethod(method, arguments);
                });
        AtomicInteger holderReads = new AtomicInteger();
        Inventory inventory = proxy(Inventory.class, (method, arguments) -> switch (method) {
            case HOLDER_METHOD -> {
                requireNonSnapshot(arguments);
                holderReads.incrementAndGet();
                yield holder;
            }
            case "getLocation" -> throw new AssertionError(
                    "Entity inventory identity must not depend on block location");
            default -> objectMethod(method, arguments);
        });
        inventoryReference.set(inventory);

        PaperPhysicalInventorySnapshot snapshot =
                PaperPhysicalInventorySnapshot.capture(inventory).orElseThrow();

        assertEquals(LocationDescriptor.Type.BLOCK_CONTAINER, snapshot.type());
        assertEquals("minecraft:overworld:entity:" + entityId, snapshot.key());
        assertEquals(1, holderReads.get(), "Entity holder must be read once");
        assertInstanceOf(PaperInventoryReference.EntityInventory.class, snapshot.reference());
    }


    @Test
    void blockInventoryReadsExpensiveBukkitHolderOnlyOnce() {
        UUID worldId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        World world = proxy(World.class, (method, arguments) -> switch (method) {
            case "getUID" -> worldId;
            case "getKey" -> NamespacedKey.minecraft("overworld");
            default -> objectMethod(method, arguments);
        });
        Location location = new Location(world, 12, 70, -8);
        InventoryHolder holder = proxy(InventoryHolder.class, (method, arguments) ->
                switch (method) {
                    case "getInventory" -> null;
                    default -> objectMethod(method, arguments);
                });
        AtomicInteger holderReads = new AtomicInteger();
        Inventory inventory = proxy(Inventory.class, (method, arguments) -> switch (method) {
            case HOLDER_METHOD -> {
                requireNonSnapshot(arguments);
                holderReads.incrementAndGet();
                yield holder;
            }
            case "getLocation" -> location;
            default -> objectMethod(method, arguments);
        });

        PaperPhysicalInventorySnapshot snapshot =
                PaperPhysicalInventorySnapshot.capture(inventory).orElseThrow();

        assertEquals(1, holderReads.get(), "Block holder must be read once per snapshot");
        assertEquals(LocationDescriptor.Type.BLOCK_CONTAINER, snapshot.type());
        assertEquals(PaperInventoryReference.blockKey(location), snapshot.key());
        assertEquals(new PaperInventoryReference.Block(worldId, 12, 70, -8),
                snapshot.reference());
    }

    @Test
    void playerInventoryTypeRemainsStableWithoutDuplicateHolderRead() {
        UUID playerId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        Player player = proxy(Player.class, (method, arguments) -> switch (method) {
            case "getUniqueId" -> playerId;
            default -> objectMethod(method, arguments);
        });
        AtomicInteger mainHolderReads = new AtomicInteger();
        PlayerInventory mainInventory = proxy(PlayerInventory.class,
                (method, arguments) -> switch (method) {
                    case HOLDER_METHOD -> {
                requireNonSnapshot(arguments);
                        mainHolderReads.incrementAndGet();
                        yield player;
                    }
                    case "getLocation" -> throw new AssertionError("Player inventories have no block location");
                    default -> objectMethod(method, arguments);
                });

        PaperPhysicalInventorySnapshot main =
                PaperPhysicalInventorySnapshot.capture(mainInventory).orElseThrow();
        assertEquals(1, mainHolderReads.get());
        assertEquals(LocationDescriptor.Type.PLAYER_INVENTORY, main.type());
        assertEquals("player:" + playerId, main.key());
        assertEquals(new PaperInventoryReference.PlayerMain(playerId), main.reference());

        AtomicInteger enderHolderReads = new AtomicInteger();
        Inventory enderInventory = proxy(Inventory.class, (method, arguments) ->
                switch (method) {
                    case HOLDER_METHOD -> {
                requireNonSnapshot(arguments);
                        enderHolderReads.incrementAndGet();
                        yield player;
                    }
                    case "getLocation" -> throw new AssertionError("Ender chest should use player identity");
                    default -> objectMethod(method, arguments);
                });
        PaperPhysicalInventorySnapshot ender =
                PaperPhysicalInventorySnapshot.capture(enderInventory).orElseThrow();
        assertEquals(1, enderHolderReads.get());
        assertEquals(LocationDescriptor.Type.PLAYER_ENDER_CHEST, ender.type());
        assertEquals(new PaperInventoryReference.PlayerEnder(playerId), ender.reference());
    }

    @Test
    void unknownOrNullInventoryDoesNotFabricatePhysicalLocation() {
        org.junit.jupiter.api.Assertions.assertTrue(
                PaperPhysicalInventorySnapshot.capture(null).isEmpty());
        AtomicInteger reads = new AtomicInteger();
        Inventory inventory = proxy(Inventory.class, (method, arguments) -> switch (method) {
            case HOLDER_METHOD -> {
                requireNonSnapshot(arguments);
                reads.incrementAndGet();
                yield null;
            }
            case "getLocation" -> null;
            default -> objectMethod(method, arguments);
        });
        org.junit.jupiter.api.Assertions.assertTrue(
                PaperPhysicalInventorySnapshot.capture(inventory).isEmpty());
        assertEquals(1, reads.get());
    }

    private static void requireNonSnapshot(Object[] arguments) {
        assertEquals(1, arguments.length, "Paper must use getHolder(false)");
        assertEquals(false, arguments[0], "Never serialize inventory holders");
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(proxy(new Class<?>[] {type}, invocation));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<?>[] types, Invocation invocation) {
        return (T) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                types,
                (proxy, method, arguments) -> invocation.invoke(
                        method.getName(), arguments == null ? new Object[0] : arguments));
    }

    private static Object objectMethod(String method, Object[] arguments) {
        return switch (method) {
            case "toString" -> "test-proxy";
            case "hashCode" -> 1;
            case "equals" -> arguments.length == 1 && arguments[0] != null;
            default -> throw new UnsupportedOperationException(method);
        };
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] arguments);
    }
}
