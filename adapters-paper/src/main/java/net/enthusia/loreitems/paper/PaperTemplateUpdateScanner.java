package net.enthusia.loreitems.paper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
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
    private static final int MAX_CONTINUATION_PASSES = 64;

    private final PaperItemIdentityCodec identityCodec = new PaperItemIdentityCodec();
    private final Map<PaperInventoryReference, ScanCursor> cursors = new HashMap<>();

    ScanResult scan(
            Inventory inventory,
            Consumer<Candidate> consumer) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(consumer, "consumer");
        PaperInventoryReference reference = PaperInventoryReference.capture(inventory)
                .orElse(null);
        if (reference == null) {
            return new ScanResult(0, false, false);
        }
        ItemStack[] contents = Objects.requireNonNull(
                inventory.getContents(), "inventory contents");
        if (contents.length == 0) {
            cursors.remove(reference);
            return new ScanResult(0, false, false);
        }

        ScanCursor cursor = cursors.getOrDefault(
                reference,
                new ScanCursor(0, Math.min(contents.length, MAX_CONTINUATION_PASSES)));
        Queue<ScanNode> nodes = rootNodes(reference, contents, cursor.rootOffset());
        PaperScanLimit limit = new PaperScanLimit(MAX_ITEMS_PER_SCAN);
        List<Candidate> candidates = new ArrayList<>();
        while (!nodes.isEmpty() && limit.hasRemaining()) {
            scanNode(nodes.remove(), limit, candidates, nodes);
        }

        submitUniqueCandidates(candidates, consumer);
        boolean limitReached = !nodes.isEmpty();
        boolean continuationRequired = limitReached && cursor.remainingPasses() > 1;
        if (continuationRequired) {
            cursors.put(
                    reference,
                    new ScanCursor(
                            (cursor.rootOffset() + 1) % contents.length,
                            cursor.remainingPasses() - 1));
        } else {
            cursors.remove(reference);
        }
        return new ScanResult(candidates.size(), limitReached, continuationRequired);
    }

    private static Queue<ScanNode> rootNodes(
            PaperInventoryReference reference,
            ItemStack[] contents,
            int rootOffset) {
        Queue<ScanNode> nodes = new ArrayDeque<>();
        for (int index = 0; index < contents.length; index++) {
            int slot = (rootOffset + index) % contents.length;
            ItemStack item = contents[slot];
            if (item != null && !item.getType().isAir()) {
                nodes.add(new ScanNode(
                        item,
                        PaperTemplateUpdateItemReference.root(reference, slot),
                        0));
            }
        }
        return nodes;
    }

    private void scanNode(
            ScanNode node,
            PaperScanLimit limit,
            List<Candidate> candidates,
            Queue<ScanNode> nodes) {
        limit.consume();
        ItemIdentityReadResult identity = identityCodec.readIdentity(node.item());
        if (identity instanceof ItemIdentityReadResult.Tracked tracked) {
            candidates.add(new Candidate(tracked.identity(), node.reference()));
        }
        if (node.depth() >= MAX_NESTING_DEPTH || !limit.hasRemaining()) {
            return;
        }
        ItemMeta meta = node.item().getItemMeta();
        enqueueShulker(meta, node.reference(), node.depth(), nodes);
        enqueueBundle(meta, node.reference(), node.depth(), nodes);
    }

    private static void enqueueShulker(
            ItemMeta meta,
            PaperTemplateUpdateItemReference reference,
            int depth,
            Queue<ScanNode> nodes) {
        if (!(meta instanceof BlockStateMeta blockMeta)) {
            return;
        }
        BlockState state = Objects.requireNonNull(
                blockMeta.getBlockState(), "shulker block state");
        if (!(state instanceof ShulkerBox shulker)) {
            return;
        }
        Inventory nestedInventory = Objects.requireNonNull(
                shulker.getInventory(), "shulker inventory");
        ItemStack[] contents = Objects.requireNonNull(
                nestedInventory.getContents(), "shulker inventory contents");
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item != null && !item.getType().isAir()) {
                nodes.add(new ScanNode(
                        item,
                        reference.nested(
                                PaperTemplateUpdateItemReference.NestedStep.shulker(slot)),
                        depth + 1));
            }
        }
    }

    private static void enqueueBundle(
            ItemMeta meta,
            PaperTemplateUpdateItemReference reference,
            int depth,
            Queue<ScanNode> nodes) {
        if (!(meta instanceof BundleMeta bundle)) {
            return;
        }
        List<ItemStack> items = bundle.getItems();
        for (int index = 0; index < items.size(); index++) {
            ItemStack item = items.get(index);
            if (item != null && !item.getType().isAir()) {
                nodes.add(new ScanNode(
                        item,
                        reference.nested(
                                PaperTemplateUpdateItemReference.NestedStep.bundle(index)),
                        depth + 1));
            }
        }
    }

    private static void submitUniqueCandidates(
            List<Candidate> candidates,
            Consumer<Candidate> consumer) {
        Map<UUID, Integer> counts = new HashMap<>();
        for (Candidate candidate : candidates) {
            counts.merge(candidate.identity().instanceId().value(), 1, Integer::sum);
        }
        candidates.removeIf(candidate ->
                counts.get(candidate.identity().instanceId().value()) != 1);
        candidates.forEach(consumer);
    }

    private record ScanNode(
            ItemStack item,
            PaperTemplateUpdateItemReference reference,
            int depth) {
        private ScanNode {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(reference, "reference");
            if (depth < 0 || depth > MAX_NESTING_DEPTH) {
                throw new IllegalArgumentException("Invalid scan depth");
            }
        }
    }

    private record ScanCursor(int rootOffset, int remainingPasses) {
        private ScanCursor {
            if (rootOffset < 0 || remainingPasses < 1) {
                throw new IllegalArgumentException("Invalid template-update scan cursor");
            }
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

    record ScanResult(
            int submitted,
            boolean limitReached,
            boolean continuationRequired) {
        ScanResult {
            if (submitted < 0) {
                throw new IllegalArgumentException("submitted must not be negative");
            }
            if (continuationRequired && !limitReached) {
                throw new IllegalArgumentException(
                        "Continuation requires a reached scan limit");
            }
        }
    }
}
