package net.enthusia.loreitems.acceptance;

import java.util.Objects;
import org.bukkit.Material;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Disposable real-Paper chest markers: each of 54 positions is distinguishable.
 * Never adds LoreItems PDC, generates an identity, or represents player data.
 */
final class DoubleChestSlotMarkers {
    private static final int HALF_SIZE = 27;
    private static final int TOTAL_SIZE = 54;

    private DoubleChestSlotMarkers() {}

    static void place(DoubleChestInventory combined) {
        if (combined.getSize() != TOTAL_SIZE) {
            throw new IllegalStateException("test chest is not 54 slots");
        }
        for (int slot = 0; slot < TOTAL_SIZE; slot++) {
            combined.setItem(slot, marker(slot));
        }
    }

    static void verifyCombined(Inventory combined) {
        if (combined.getSize() != TOTAL_SIZE) {
            throw new IllegalStateException("resolved chest is not 54 slots");
        }
        for (int slot = 0; slot < TOTAL_SIZE; slot++) {
            if (!Objects.equals(marker(slot), combined.getItem(slot))) {
                throw new IllegalStateException("wrong real-Paper composite slot " + slot);
            }
        }
    }

    static void verifyHalves(Inventory left, Inventory right) {
        if (left == null || right == null
                || left.getSize() != HALF_SIZE || right.getSize() != HALF_SIZE) {
            throw new IllegalStateException("real-Paper halves are not both 27 slots");
        }
        for (int slot = 0; slot < HALF_SIZE; slot++) {
            if (!Objects.equals(marker(slot), left.getItem(slot))) {
                throw new IllegalStateException("wrong real-Paper left-half slot " + slot);
            }
            if (!Objects.equals(marker(slot + HALF_SIZE), right.getItem(slot))) {
                throw new IllegalStateException("wrong real-Paper right-half slot " + slot);
            }
        }
    }

    private static ItemStack marker(int slot) {
        return switch (slot) {
            case 0 -> new ItemStack(Material.COBBLESTONE);
            case 26 -> new ItemStack(Material.GRANITE);
            case 27 -> new ItemStack(Material.DIORITE);
            case 53 -> new ItemStack(Material.ANDESITE);
            default -> new ItemStack(Material.PAPER, slot + 1);
        };
    }
}
