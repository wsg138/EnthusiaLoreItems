package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.Test;

class PaperPhysicalInventorySnapshotTest {
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
        Inventory inventory = proxy(Inventory.class, (method, arguments) -> switch (method) {
            case "getHolder" -> holder;
            case "getLocation" -> throw new AssertionError(
                    "Entity inventory identity must not depend on block location");
            default -> objectMethod(method, arguments);
        });
        inventoryReference.set(inventory);

        PaperPhysicalInventorySnapshot snapshot =
                PaperPhysicalInventorySnapshot.capture(inventory).orElseThrow();

        assertEquals(LocationDescriptor.Type.BLOCK_CONTAINER, snapshot.type());
        assertEquals("minecraft:overworld:entity:" + entityId, snapshot.key());
        assertInstanceOf(PaperInventoryReference.EntityInventory.class, snapshot.reference());
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
