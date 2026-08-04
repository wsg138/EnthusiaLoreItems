package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

/** Shared bounded collector for tracked identities, including nested shulkers and bundles. */
final class PaperTrackedItemCollector {
    private static final int MAX_NESTING_DEPTH = 8;

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
        for (int index = 0; index < contents.length && limit.hasRemaining(); index++) {
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
        if (item == null || item.getType().isAir() || !limit.hasRemaining()) {
            return;
        }
        limit.consume();
        LoreItemIdentity identity = trackedIdentity(item);
        if (identity != null) {
            observations.computeIfAbsent(identity, ignored -> new ArrayList<>())
                    .add(new LocationDescriptor(type, key, path));
        }
        if (depth >= MAX_NESTING_DEPTH || !limit.hasRemaining()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BlockStateMeta blockMeta) {
            BlockState blockState = blockMeta.getBlockState();
            if (blockState instanceof ShulkerBox shulker) {
                collectNested(
                        shulker.getInventory().getContents(),
                        key,
                        path + "/shulker:",
                        observations,
                        depth,
                        limit);
            }
        }
        if (meta instanceof BundleMeta bundle) {
            collectNested(
                    bundle.getItems().toArray(ItemStack[]::new),
                    key,
                    path + "/bundle:",
                    observations,
                    depth,
                    limit);
        }
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

    private void collectNested(
            ItemStack[] contents,
            String key,
            String prefix,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            int depth,
            PaperScanLimit limit) {
        if (contents == null) {
            return;
        }
        for (int index = 0; index < contents.length && limit.hasRemaining(); index++) {
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
}
