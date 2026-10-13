package net.enthusia.loreitems.acceptance;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Test-only, fail-closed bridge to the loaded production JAR on real Paper.
 * Does not create tracked item identities, modify plugin permissions or touch SQLite.
 */
final class ProductionDoubleChestProbe {
    private ProductionDoubleChestProbe() {}

    static String verify(
            World world, DoubleChestPaperFixture.Pair pair,
            DoubleChestInventory combined, boolean firstIsPaperLeft) {
        if (!pair.loaded(world)) {
            throw new IllegalStateException("production probe would read an unloaded chest half");
        }
        Plugin production = Objects.requireNonNull(
                Bukkit.getPluginManager().getPlugin("EnthusiaLoreItems"), "production plugin");
        if (!production.isEnabled()) {
            throw new IllegalStateException("production LoreItems is not enabled");
        }
        Chest first = chest(world, pair.x1(), pair.z1());
        Chest second = chest(world, pair.x2(), pair.z2());
        String expectedKey = "double-chest:" + world.getUID() + ':'
                + pair.x1() + ":70:" + pair.z1() + '|'
                + pair.x2() + ":70:" + pair.z2()
                + ":left=" + (firstIsPaperLeft ? "0" : "1");
        try {
            Class<?> snapshotType = Class.forName(
                    "net.enthusia.loreitems.paper.PaperPhysicalInventorySnapshot",
                    true, production.getClass().getClassLoader());
            Method capture = snapshotType.getDeclaredMethod(
                    "captureResolved", Inventory.class, Plugin.class);
            capture.setAccessible(true);
            List<Inventory> sources = List.of(
                    combined, combined.getLeftSide(), combined.getRightSide(),
                    first.getBlockInventory(), second.getBlockInventory());
            for (Inventory source : sources) {
                Object view = ((Optional<?>) capture.invoke(null, source, production))
                        .orElseThrow(() -> new IllegalStateException(
                                "production failed to capture a loaded real-Paper chest half"));
                Method snapshot = view.getClass().getDeclaredMethod("snapshot");
                snapshot.setAccessible(true);
                Object recorded = snapshot.invoke(view);
                Method key = snapshotType.getDeclaredMethod("key");
                key.setAccessible(true);
                String actualKey = (String) key.invoke(recorded);
                if (!expectedKey.equals(actualKey)) {
                    throw new IllegalStateException("production chest key mismatch: expected="
                            + expectedKey + " actual=" + actualKey);
                }
                Method inventory = view.getClass().getDeclaredMethod("inventory");
                inventory.setAccessible(true);
                verifyBoundary((Inventory) inventory.invoke(view));
            }
            return expectedKey;
        } catch (ReflectiveOperationException | ClassCastException exception) {
            throw new IllegalStateException("production snapshot probe failed closed", exception);
        }
    }

    private static Chest chest(World world, int x, int z) {
        if (!(world.getBlockAt(x, 70, z).getState(false) instanceof Chest chest)) {
            throw new IllegalStateException("production probe chest half is missing");
        }
        return chest;
    }

    private static void verifyBoundary(Inventory resolved) {
        if (!(resolved instanceof DoubleChestInventory) || resolved.getSize() != 54) {
            throw new IllegalStateException("production resolved half as non-composite inventory");
        }
        requireMaterial(resolved.getItem(0), Material.COBBLESTONE);
        requireMaterial(resolved.getItem(26), Material.GRANITE);
        requireMaterial(resolved.getItem(27), Material.DIORITE);
        requireMaterial(resolved.getItem(53), Material.ANDESITE);
    }

    private static void requireMaterial(ItemStack item, Material expected) {
        if (item == null || item.getType() != expected) {
            throw new IllegalStateException("production 54-slot physical boundary mismatch " + expected);
        }
    }
}
