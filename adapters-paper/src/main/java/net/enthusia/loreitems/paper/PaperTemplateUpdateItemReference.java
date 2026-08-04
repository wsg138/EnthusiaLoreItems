package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/** Immutable, reload-safe path to one item inside a naturally accessible inventory. */
record PaperTemplateUpdateItemReference(
        PaperInventoryReference inventoryReference,
        int rootSlot,
        List<NestedStep> nestedPath) {
    private static final int MAX_NESTING_DEPTH = 8;

    PaperTemplateUpdateItemReference {
        Objects.requireNonNull(inventoryReference, "inventoryReference");
        Objects.requireNonNull(nestedPath, "nestedPath");
        if (rootSlot < 0) {
            throw new IllegalArgumentException("rootSlot must not be negative");
        }
        if (nestedPath.size() > MAX_NESTING_DEPTH) {
            throw new IllegalArgumentException("nestedPath exceeds the supported depth");
        }
        nestedPath = List.copyOf(nestedPath);
    }

    static PaperTemplateUpdateItemReference root(
            PaperInventoryReference inventoryReference,
            int rootSlot) {
        return new PaperTemplateUpdateItemReference(
                inventoryReference, rootSlot, List.of());
    }

    PaperTemplateUpdateItemReference nested(NestedStep step) {
        Objects.requireNonNull(step, "step");
        List<NestedStep> path = new ArrayList<>(nestedPath);
        path.add(step);
        return new PaperTemplateUpdateItemReference(inventoryReference, rootSlot, path);
    }

    Optional<Resolved> resolve(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        Optional<Inventory> resolvedInventory = inventoryReference.resolve(plugin);
        if (resolvedInventory.isEmpty()) {
            return Optional.empty();
        }
        Inventory inventory = resolvedInventory.orElseThrow();
        if (rootSlot >= inventory.getSize()) {
            return Optional.empty();
        }
        ItemStack root = inventory.getItem(rootSlot);
        ItemStack target = readAt(root, nestedPath, 0);
        if (target == null || target.getType().isAir()) {
            return Optional.empty();
        }
        return Optional.of(new Resolved(
                inventory,
                rootSlot,
                nestedPath,
                Objects.requireNonNull(root, "root").clone(),
                target.clone()));
    }

    private static ItemStack readAt(
            ItemStack item,
            List<NestedStep> path,
            int depth) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        if (depth == path.size()) {
            return item;
        }
        NestedStep step = path.get(depth);
        ItemMeta meta = item.getItemMeta();
        ItemStack child = switch (step.kind()) {
            case SHULKER -> shulkerChild(meta, step.index());
            case BUNDLE -> bundleChild(meta, step.index());
        };
        return readAt(child, path, depth + 1);
    }

    private static ItemStack shulkerChild(ItemMeta meta, int index) {
        if (!(meta instanceof BlockStateMeta blockMeta)) {
            return null;
        }
        BlockState state = blockMeta.getBlockState();
        if (!(state instanceof ShulkerBox shulker)
                || index >= shulker.getInventory().getSize()) {
            return null;
        }
        return shulker.getInventory().getItem(index);
    }

    private static ItemStack bundleChild(ItemMeta meta, int index) {
        if (!(meta instanceof BundleMeta bundle)) {
            return null;
        }
        List<ItemStack> items = bundle.getItems();
        return index < items.size() ? items.get(index) : null;
    }

    private static ItemStack replaceAt(
            ItemStack item,
            List<NestedStep> path,
            int depth,
            ItemStack replacement) {
        if (depth == path.size()) {
            return replacement.clone();
        }
        ItemStack parent = Objects.requireNonNull(item, "nested parent").clone();
        NestedStep step = path.get(depth);
        boolean applied = switch (step.kind()) {
            case SHULKER -> replaceShulkerChild(parent, path, depth, step.index(), replacement);
            case BUNDLE -> replaceBundleChild(parent, path, depth, step.index(), replacement);
        };
        if (!applied) {
            return null;
        }
        return parent;
    }

    private static boolean replaceShulkerChild(
            ItemStack parent,
            List<NestedStep> path,
            int depth,
            int index,
            ItemStack replacement) {
        ItemMeta meta = parent.getItemMeta();
        if (!(meta instanceof BlockStateMeta blockMeta)) {
            return false;
        }
        BlockState state = blockMeta.getBlockState();
        if (!(state instanceof ShulkerBox shulker)
                || index >= shulker.getInventory().getSize()) {
            return false;
        }
        ItemStack child = shulker.getInventory().getItem(index);
        ItemStack updated = replaceAt(child, path, depth + 1, replacement);
        if (updated == null) {
            return false;
        }
        shulker.getInventory().setItem(index, updated);
        blockMeta.setBlockState(shulker);
        return parent.setItemMeta(blockMeta);
    }

    private static boolean replaceBundleChild(
            ItemStack parent,
            List<NestedStep> path,
            int depth,
            int index,
            ItemStack replacement) {
        ItemMeta meta = parent.getItemMeta();
        if (!(meta instanceof BundleMeta bundle)) {
            return false;
        }
        List<ItemStack> items = new ArrayList<>(bundle.getItems());
        if (index >= items.size()) {
            return false;
        }
        ItemStack updated = replaceAt(items.get(index), path, depth + 1, replacement);
        if (updated == null) {
            return false;
        }
        items.set(index, updated);
        bundle.setItems(items);
        return parent.setItemMeta(bundle);
    }

    enum NestedKind {
        SHULKER,
        BUNDLE
    }

    record NestedStep(NestedKind kind, int index) {
        NestedStep {
            Objects.requireNonNull(kind, "kind");
            if (index < 0) {
                throw new IllegalArgumentException("Nested item index must not be negative");
            }
        }

        static NestedStep shulker(int index) {
            return new NestedStep(NestedKind.SHULKER, index);
        }

        static NestedStep bundle(int index) {
            return new NestedStep(NestedKind.BUNDLE, index);
        }
    }

    static final class Resolved {
        private final Inventory inventory;
        private final int rootSlot;
        private final List<NestedStep> nestedPath;
        private final ItemStack originalRoot;
        private final ItemStack originalItem;

        private Resolved(
                Inventory inventory,
                int rootSlot,
                List<NestedStep> nestedPath,
                ItemStack originalRoot,
                ItemStack originalItem) {
            this.inventory = inventory;
            this.rootSlot = rootSlot;
            this.nestedPath = nestedPath;
            this.originalRoot = originalRoot;
            this.originalItem = originalItem;
        }

        ItemStack originalItem() {
            return originalItem.clone();
        }

        boolean replace(ItemStack replacement) {
            ItemStack updatedRoot = replaceAt(originalRoot, nestedPath, 0, replacement);
            if (updatedRoot == null) {
                return false;
            }
            inventory.setItem(rootSlot, updatedRoot);
            return true;
        }

        ItemStack readStored() {
            ItemStack storedRoot = inventory.getItem(rootSlot);
            ItemStack stored = readAt(storedRoot, nestedPath, 0);
            return stored == null ? null : stored.clone();
        }

        boolean restore() {
            inventory.setItem(rootSlot, originalRoot.clone());
            ItemStack restored = readStored();
            return restored != null
                    && PaperItemFingerprint.of(restored)
                            .equals(PaperItemFingerprint.of(originalItem));
        }
    }
}
