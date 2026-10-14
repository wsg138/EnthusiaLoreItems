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
    // Production record holds only immutable UUID + block-coordinate reference.
    // Never store a Bukkit holder, inventory, block or live chunk across ticks.
    private static Object remoteSnapshot;

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
            Object canonicalSnapshot = null;
            for (Inventory source : sources) {
                Object recorded = captureAndVerify(source, capture, snapshotType,
                        production, expectedKey);
                if (canonicalSnapshot == null) {
                    canonicalSnapshot = recorded;
                }
            }
            if (pair.x1() == 2047 && pair.x2() == 2048) {
                remoteSnapshot = Objects.requireNonNull(canonicalSnapshot, "remote snapshot");
            }
            return expectedKey;
        } catch (ReflectiveOperationException | ClassCastException exception) {
            throw new IllegalStateException("production snapshot probe failed closed", exception);
        }
    }

    private static Object captureAndVerify(
            Inventory source, Method capture, Class<?> snapshotType,
            Plugin production, String expectedKey) throws ReflectiveOperationException {
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
        return recorded;
    }

    /**
     * Prove the ACTUAL production resolver cannot read the absent second half.
     * The stored record was captured while the pair was fully loaded and
     * retains UUIDs/coordinates only. The call must not change either chunk's
     * natural loaded state or produce a 54-slot inventory.
     */
    static void verifyIncomplete(
            World world, DoubleChestPaperFixture.Pair pair) {
        Object snapshot = Objects.requireNonNull(remoteSnapshot,
                "remote production snapshot was never captured while both chunks were loaded");
        boolean first = world.isChunkLoaded(pair.chunkX1(), pair.chunkZ1());
        boolean second = world.isChunkLoaded(pair.chunkX2(), pair.chunkZ2());
        if (first == second) {
            throw new IllegalStateException("production negative probe requires exactly one loaded half");
        }
        Plugin production = Objects.requireNonNull(
                Bukkit.getPluginManager().getPlugin("EnthusiaLoreItems"), "production plugin");
        try {
            Method resolve = snapshot.getClass().getDeclaredMethod("resolve", Plugin.class);
            resolve.setAccessible(true);
            Object result = resolve.invoke(snapshot, production);
            if (!(result instanceof Optional<?> contents) || contents.isPresent()) {
                throw new IllegalStateException(
                        "production wrongly resolved a 54-slot inventory with one unloaded half");
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("production incomplete resolver probe failed closed",
                    exception);
        }
        if (first != world.isChunkLoaded(pair.chunkX1(), pair.chunkZ1())
                || second != world.isChunkLoaded(pair.chunkX2(), pair.chunkZ2())) {
            throw new IllegalStateException("production resolver loaded an absent chest chunk");
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
