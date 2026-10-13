package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.lang.reflect.Proxy;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

/** Synthetic collision oracle; intentionally fails until world UUID is part of durable identity. */
class DoubleChestWorldIdentityContractTest {
    @Test
    void differentWorldUuidWithSameNamespacedWorldKeyCannotSharePhysicalChestKey() {
        World original = world(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        World copied = world(UUID.fromString("22222222-2222-2222-2222-222222222222"));

        String before = PaperInventoryReference.blockKey(new Location(original, 15, 70, 15));
        String after = PaperInventoryReference.blockKey(new Location(copied, 15, 70, 15));

        assertNotEquals(before, after,
                "A restored/copied world UUID must not reuse another world's physical chest key");
    }

    private static World world(UUID id) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getKey" -> NamespacedKey.minecraft("same_world_name");
                    case "getUID" -> id;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args != null && args.length == 1 && args[0] == proxy;
                    case "toString" -> "synthetic-world-" + id;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
