package net.enthusia.loreitems.paper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Canonical comparison for a physically stored template-update result.
 *
 * <p>Only hidden lore identity keys and mutable container contents are normalized. Mutable
 * contents are compared recursively, so visible template data and every retained item still have
 * to match.</p>
 */
final class PaperTemplateItemComparator {
    private static final int MAX_NESTING_DEPTH = 8;

    private final PaperItemIdentityCodec identityCodec;

    PaperTemplateItemComparator(PaperItemIdentityCodec identityCodec) {
        this.identityCodec = Objects.requireNonNull(identityCodec, "identityCodec");
    }

    boolean matches(ItemStack first, ItemStack second) {
        return matches(first, second, 0);
    }

    private boolean matches(ItemStack first, ItemStack second, int depth) {
        if (first == null || second == null || depth > MAX_NESTING_DEPTH) {
            return first == null && second == null;
        }
        if (first.getType() != second.getType() || first.getAmount() != second.getAmount()) {
            return false;
        }
        ItemStack firstShell = normalizedShell(first);
        ItemStack secondShell = normalizedShell(second);
        if (!sameShell(firstShell, secondShell)) {
            return false;
        }
        return sameShulkerContents(first, second, depth)
                && sameBundleContents(first, second, depth);
    }

    private ItemStack normalizedShell(ItemStack item) {
        ItemStack shell = identityCodec.clearIdentity(item);
        clearShulkerContents(shell);
        clearBundleContents(shell);
        return shell;
    }

    private static boolean sameShell(ItemStack first, ItemStack second) {
        if (first.isSimilar(second)) {
            return true;
        }
        Map<String, Object> firstSerialized = first.serialize();
        Map<String, Object> secondSerialized = second.serialize();
        return firstSerialized.equals(secondSerialized);
    }

    private boolean sameShulkerContents(ItemStack first, ItemStack second, int depth) {
        ItemStack[] firstContents = shulkerContents(first);
        ItemStack[] secondContents = shulkerContents(second);
        if (firstContents == null || secondContents == null) {
            return firstContents == null && secondContents == null;
        }
        if (firstContents.length != secondContents.length) {
            return false;
        }
        for (int slot = 0; slot < firstContents.length; slot++) {
            if (!sameNullableItem(firstContents[slot], secondContents[slot], depth + 1)) {
                return false;
            }
        }
        return true;
    }

    private boolean sameBundleContents(ItemStack first, ItemStack second, int depth) {
        List<ItemStack> firstContents = bundleContents(first);
        List<ItemStack> secondContents = bundleContents(second);
        if (firstContents == null || secondContents == null) {
            return firstContents == null && secondContents == null;
        }
        if (firstContents.size() != secondContents.size()) {
            return false;
        }
        for (int index = 0; index < firstContents.size(); index++) {
            if (!sameNullableItem(firstContents.get(index), secondContents.get(index), depth + 1)) {
                return false;
            }
        }
        return true;
    }

    private boolean sameNullableItem(ItemStack first, ItemStack second, int depth) {
        boolean firstEmpty = first == null || first.getType().isAir();
        boolean secondEmpty = second == null || second.getType().isAir();
        if (firstEmpty || secondEmpty) {
            return firstEmpty && secondEmpty;
        }
        return matches(first, second, depth);
    }

    private static void clearShulkerContents(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof BlockStateMeta blockMeta)) {
            return;
        }
        BlockState state = Objects.requireNonNull(
                blockMeta.getBlockState(), "shulker block state");
        if (!(state instanceof ShulkerBox shulker)) {
            return;
        }
        Inventory inventory = Objects.requireNonNull(
                shulker.getInventory(), "shulker inventory");
        inventory.clear();
        blockMeta.setBlockState(shulker);
        if (!item.setItemMeta(blockMeta)) {
            throw new IllegalArgumentException("Paper rejected normalized shulker metadata");
        }
    }

    private static void clearBundleContents(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof BundleMeta bundle)) {
            return;
        }
        bundle.setItems(List.of());
        if (!item.setItemMeta(bundle)) {
            throw new IllegalArgumentException("Paper rejected normalized bundle metadata");
        }
    }

    private static ItemStack[] shulkerContents(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof BlockStateMeta blockMeta)) {
            return null;
        }
        BlockState state = Objects.requireNonNull(
                blockMeta.getBlockState(), "shulker block state");
        if (!(state instanceof ShulkerBox shulker)) {
            return null;
        }
        Inventory inventory = Objects.requireNonNull(
                shulker.getInventory(), "shulker inventory");
        return Objects.requireNonNull(inventory.getContents(), "shulker contents");
    }

    private static List<ItemStack> bundleContents(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta instanceof BundleMeta bundle ? bundle.getItems() : null;
    }
}
