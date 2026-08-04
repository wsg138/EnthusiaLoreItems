package net.enthusia.loreitems.paper;

import java.util.Arrays;
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
 * <p>Only the root lore identity keys and mutable container contents are normalized. Mutable
 * contents are compared recursively, so visible template data, retained items, and nested lore
 * identities still have to match.</p>
 */
final class PaperTemplateItemComparator {
    private static final int MAX_NESTING_DEPTH = 8;

    private final PaperItemIdentityCodec identityCodec;

    PaperTemplateItemComparator(PaperItemIdentityCodec identityCodec) {
        this.identityCodec = Objects.requireNonNull(identityCodec, "identityCodec");
    }

    boolean matches(ItemStack first, ItemStack second) {
        return matches(first, second, 0, true);
    }

    private boolean matches(
            ItemStack first,
            ItemStack second,
            int depth,
            boolean normalizeRootIdentity) {
        if (first == null || second == null) {
            return first == second;
        }
        if (depth > MAX_NESTING_DEPTH || !sameBasicProperties(first, second)) {
            return false;
        }
        ItemStack firstShell = normalizedShell(first, normalizeRootIdentity);
        ItemStack secondShell = normalizedShell(second, normalizeRootIdentity);
        return sameShell(firstShell, secondShell)
                && sameContents(shulkerContents(first), shulkerContents(second), depth)
                && sameContents(bundleContents(first), bundleContents(second), depth);
    }

    private static boolean sameBasicProperties(ItemStack first, ItemStack second) {
        return first.getType() == second.getType() && first.getAmount() == second.getAmount();
    }

    private ItemStack normalizedShell(ItemStack item, boolean normalizeRootIdentity) {
        ItemStack shell = normalizeRootIdentity
                ? identityCodec.clearIdentity(item)
                : item.clone();
        clearMutableContents(shell);
        return shell;
    }

    static void clearMutableContents(ItemStack item) {
        Objects.requireNonNull(item, "item");
        clearShulkerContents(item);
        clearBundleContents(item);
    }

    private static boolean sameShell(ItemStack first, ItemStack second) {
        if (first.isSimilar(second)) {
            return true;
        }
        Map<String, Object> firstSerialized = first.serialize();
        Map<String, Object> secondSerialized = second.serialize();
        return firstSerialized.equals(secondSerialized);
    }

    private boolean sameContents(
            List<ItemStack> firstContents,
            List<ItemStack> secondContents,
            int depth) {
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
        return matches(first, second, depth, false);
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

    private static List<ItemStack> shulkerContents(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof BlockStateMeta blockMeta)) {
            return List.of();
        }
        BlockState state = Objects.requireNonNull(
                blockMeta.getBlockState(), "shulker block state");
        if (!(state instanceof ShulkerBox shulker)) {
            return List.of();
        }
        Inventory inventory = Objects.requireNonNull(
                shulker.getInventory(), "shulker inventory");
        ItemStack[] contents = Objects.requireNonNull(
                inventory.getContents(), "shulker contents");
        return Arrays.asList(contents);
    }

    private static List<ItemStack> bundleContents(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta instanceof BundleMeta bundle ? bundle.getItems() : List.of();
    }
}
