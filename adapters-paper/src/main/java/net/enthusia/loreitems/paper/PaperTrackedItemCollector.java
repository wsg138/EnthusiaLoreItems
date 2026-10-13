package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

/** Shared bounded collector for tracked identities, including nested shulkers and bundles. */
final class PaperTrackedItemCollector {
    private static final int MAX_NESTING_DEPTH = 8;
    private static final String ROOT_KEY_PREFIX = "root:";

    private final PaperItemIdentityCodec identityCodec = new PaperItemIdentityCodec();

    void collectArray(
            ItemStack[] contents,
            LocationDescriptor.Type type,
            String key,
            String prefix,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            PaperScanLimit limit) {
        if (contents == null) {
            return;
        }
        for (int index = 0; index < contents.length; index++) {
            if (!limit.canVisit()) {
                limit.markTruncated();
                return;
            }
            collectItem(contents[index], type, key, prefix + index, observations, 0, limit);
        }
    }

    void collectItem(
            ItemStack item,
            LocationDescriptor.Type type,
            String key,
            String path,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            int depth,
            PaperScanLimit limit) {
        // Charge even empty slots; otherwise huge sparse inventories appear "complete".
        if (!limit.tryConsume() || !scannable(item)) {
            return;
        }
        collectIdentity(item, type, key, path, observations);
        // Never recursively pre-scan metadata for a positive identity hint:
        // the evidence probe itself could visit an unbounded subtree.
        NestedEvidence evidence = nestedContents(item);
        if (evidence == null) {
            return;
        }
        if (depth == MAX_NESTING_DEPTH) {
            limit.markTruncated();
            return;
        }
        collectNested(evidence, nestedLocationKey(type, key),
                path, observations, depth, limit);
    }

    /** Single decode so bounded scans can distinguish malformed identity from absence. */
    ItemIdentityReadResult identityResult(ItemStack item) {
        return identityCodec.readIdentity(item);
    }

    LoreItemIdentity trackedIdentity(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        ItemIdentityReadResult result = identityCodec.readIdentity(item);
        return result instanceof ItemIdentityReadResult.Tracked tracked
                ? tracked.identity()
                : null;
    }

    /**
     * Conservative event hint, NOT a proof of presence. When an evidence walk
     * reaches its cap, report relevant so the listener reconciles rather than
     * silently skipping a potentially tracked nested item.
     */
    boolean hasIdentityEvidence(ItemStack item) {
        return hasIdentityEvidence(item, 0, new PaperScanLimit(256));
    }

    boolean hasNestedIdentityEvidence(ItemStack item) {
        return nestedEvidenceWithIdentityEvidence(item) != null;
    }

    NestedEvidence nestedEvidenceWithIdentityEvidence(ItemStack item) {
        NestedEvidence evidence = nestedContents(item);
        return evidence != null
                && hasNestedIdentityEvidence(evidence, 0, new PaperScanLimit(256))
                ? evidence : null;
    }

    record NestedEvidence(ItemMeta meta, BlockState blockState) {}

    /** Metadata discovery only; traversal must charge each nested slot to a budget. */
    NestedEvidence nestedContents(ItemStack item) {
        if (!scannable(item) || !canHaveNestedContents(item.getType())
                || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BlockStateMeta block) {
            return new NestedEvidence(meta, block.getBlockState());
        }
        return meta instanceof BundleMeta ? new NestedEvidence(meta, null) : null;
    }

    static String nestedLocationKey(LocationDescriptor.Type parentType, String locationKey) {
        Objects.requireNonNull(parentType, "parentType");
        Objects.requireNonNull(locationKey, "locationKey");
        if (parentType == LocationDescriptor.Type.NESTED_CONTAINER) {
            return locationKey;
        }
        return ROOT_KEY_PREFIX + parentType.name() + ':' + locationKey;
    }

    private boolean hasIdentityEvidence(ItemStack item, int depth, PaperScanLimit budget) {
        if (!budget.tryConsume()) {
            return true; // Unknown: fail closed by scheduling reconciliation.
        }
        if (!scannable(item)) {
            return false;
        }
        if (identityCodec.hasIdentityEvidence(item)) {
            return true;
        }
        NestedEvidence evidence = nestedContents(item);
        if (evidence == null) {
            return false;
        }
        return depth >= MAX_NESTING_DEPTH
                || hasNestedIdentityEvidence(evidence, depth, budget);
    }

    private boolean hasNestedIdentityEvidence(NestedEvidence evidence, int depth,
                                              PaperScanLimit budget) {
        if (evidence.blockState() instanceof ShulkerBox box) {
            return hasInventoryEvidence(box.getInventory(), depth, budget);
        }
        return evidence.meta() instanceof BundleMeta bundle
                && hasBundleEvidence(bundle, depth, budget);
    }

    private boolean hasInventoryEvidence(org.bukkit.inventory.Inventory inventory,
                                         int depth, PaperScanLimit budget) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (hasIdentityEvidence(inventory.getItem(slot), depth + 1, budget)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBundleEvidence(BundleMeta bundle, int depth, PaperScanLimit budget) {
        for (ItemStack nested : bundle.getItems()) {
            if (hasIdentityEvidence(nested, depth + 1, budget)) {
                return true;
            }
        }
        return false;
    }

    private void collectIdentity(
            ItemStack item,
            LocationDescriptor.Type type,
            String key,
            String path,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations) {
        LoreItemIdentity identity = trackedIdentity(item);
        if (identity != null) {
            observations.computeIfAbsent(identity, ignored -> new ArrayList<>())
                    .add(new LocationDescriptor(type, key, path));
        }
    }

    private void collectNested(
            NestedEvidence evidence,
            String key,
            String path,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            int depth,
            PaperScanLimit limit) {
        if (evidence.meta() instanceof BlockStateMeta) {
            collectShulker(evidence.blockState(), key, path, observations, depth, limit);
        }
        if (evidence.meta() instanceof BundleMeta bundle && limit.tryConsume()) {
            collectNestedArray(
                    bundle.getItems().toArray(ItemStack[]::new),
                    key,
                    path + "/bundle:",
                    observations,
                    depth,
                    limit);
        }
    }

    private void collectShulker(
            BlockState blockState,
            String key,
            String path,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            int depth,
            PaperScanLimit limit) {
        if (blockState instanceof ShulkerBox shulker && limit.tryConsume()) {
            collectNestedArray(
                    shulker.getInventory().getContents(),
                    key,
                    path + "/shulker:",
                    observations,
                    depth,
                    limit);
        }
    }

    private void collectNestedArray(
            ItemStack[] contents,
            String key,
            String prefix,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            int depth,
            PaperScanLimit limit) {
        if (contents == null) {
            return;
        }
        for (int index = 0; index < contents.length; index++) {
            if (!limit.canVisit()) {
                limit.markTruncated();
                return;
            }
            collectItem(
                    contents[index],
                    LocationDescriptor.Type.NESTED_CONTAINER,
                    key,
                    prefix + index,
                    observations,
                    depth + 1,
                    limit);
        }
    }

    /** Only shulker boxes and bundles can contain the nested items we inspect. */
    private static boolean canHaveNestedContents(Material material) {
        String name = material.name();
        return name.endsWith("SHULKER_BOX") || name.endsWith("BUNDLE");
    }

    private static boolean scannable(ItemStack item) {
        return item != null && !item.getType().isAir();
    }
}
