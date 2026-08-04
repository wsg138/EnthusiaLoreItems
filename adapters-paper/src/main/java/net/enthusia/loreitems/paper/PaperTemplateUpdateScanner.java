package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

/** Bounded main-thread discovery of uniquely located tracked items in one inventory tree. */
final class PaperTemplateUpdateScanner {
    private static final int MAX_ITEMS_PER_SCAN = 256;
    private static final int MAX_NESTING_DEPTH = 8;

    private final PaperItemIdentityCodec identityCodec = new PaperItemIdentityCodec();

    ScanResult scan(
            Inventory inventory,
            Consumer<Candidate> consumer) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(consumer, "consumer");
        PaperInventoryReference reference = PaperInventoryReference.capture(inventory)
                .orElse(null);
        if (reference == null) {
            return new ScanResult(0, false);
        }

        PaperScanLimit limit = new PaperScanLimit(MAX_ITEMS_PER_SCAN);
        List<Candidate> candidates = new ArrayList<>();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length && limit.hasRemaining(); slot++) {
            scanItem(
                    contents[slot],
                    PaperTemplateUpdateItemReference.root(reference, slot),
                    0,
                    limit,
                    candidates);
        }

        Map<UUID, Integer> counts = new HashMap<>();
        for (Candidate candidate : candidates) {
            counts.merge(candidate.identity().instanceId().value(), 1, Integer::sum);
        }
        int submitted = 0;
        for (Candidate candidate : candidates) {
            if (counts.get(candidate.identity().instanceId().value()) == 1) {
                consumer.accept(candidate);
                submitted++;
            }
        }
        return new ScanResult(submitted, !limit.hasRemaining());
    }

    private void scanItem(
            ItemStack item,
            PaperTemplateUpdateItemReference reference,
            int depth,
            PaperScanLimit limit,
            List<Candidate> candidates) {
        if (item == null || item.getType().isAir() || !limit.hasRemaining()) {
            return;
        }
        limit.consume();
        ItemIdentityReadResult identity = identityCodec.readIdentity(item);
        if (identity instanceof ItemIdentityReadResult.Tracked tracked) {
            candidates.add(new Candidate(tracked.identity(), reference));
        }
        if (depth >= MAX_NESTING_DEPTH || !limit.hasRemaining()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        scanShulker(meta, reference, depth, limit, candidates);
        scanBundle(meta, reference, depth, limit, candidates);
    }

    private void scanShulker(
            ItemMeta meta,
            PaperTemplateUpdateItemReference reference,
            int depth,
            PaperScanLimit limit,
            List<Candidate> candidates) {
        if (!(meta instanceof BlockStateMeta blockMeta)) {
            return;
        }
        BlockState state = blockMeta.getBlockState();
        if (!(state instanceof ShulkerBox shulker)) {
            return;
        }
        ItemStack[] contents = shulker.getInventory().getContents();
        for (int slot = 0; slot < contents.length && limit.hasRemaining(); slot++) {
            scanItem(
                    contents[slot],
                    reference.nested(
                            PaperTemplateUpdateItemReference.NestedStep.shulker(slot)),
                    depth + 1,
                    limit,
                    candidates);
        }
    }

    private void scanBundle(
            ItemMeta meta,
            PaperTemplateUpdateItemReference reference,
            int depth,
            PaperScanLimit limit,
            List<Candidate> candidates) {
        if (!(meta instanceof BundleMeta bundle)) {
            return;
        }
        List<ItemStack> items = bundle.getItems();
        for (int index = 0; index < items.size() && limit.hasRemaining(); index++) {
            scanItem(
                    items.get(index),
                    reference.nested(
                            PaperTemplateUpdateItemReference.NestedStep.bundle(index)),
                    depth + 1,
                    limit,
                    candidates);
        }
    }

    record Candidate(
            LoreItemIdentity identity,
            PaperTemplateUpdateItemReference reference) {
        Candidate {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(reference, "reference");
        }
    }

    record ScanResult(int submitted, boolean truncated) {
        ScanResult {
            if (submitted < 0) {
                throw new IllegalArgumentException("submitted must not be negative");
            }
        }
    }
}
