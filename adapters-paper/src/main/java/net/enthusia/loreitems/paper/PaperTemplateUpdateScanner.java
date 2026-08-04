package net.enthusia.loreitems.paper;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import org.bukkit.plugin.Plugin;

/** Bounded main-thread discovery of uniquely located tracked items in one inventory tree. */
final class PaperTemplateUpdateScanner {
    private static final int MAX_ITEMS_PER_PASS = 256;
    private static final int MAX_NESTING_DEPTH = 8;
    private static final int MAX_CONTINUATION_PASSES = 64;
    private static final int MAX_PENDING_REFERENCES = 8_192;

    private final PaperItemIdentityCodec identityCodec = new PaperItemIdentityCodec();
    private final Map<PaperInventoryReference, ScanCursor> cursors = new HashMap<>();

    ScanResult scan(
            Plugin plugin,
            Inventory inventory,
            Consumer<Candidate> consumer) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(consumer, "consumer");
        PaperInventoryReference inventoryReference = PaperInventoryReference.capture(inventory)
                .orElse(null);
        if (inventoryReference == null) {
            return ScanResult.complete(0);
        }

        ScanCursor cursor = cursors.computeIfAbsent(
                inventoryReference,
                ignored -> createCursor(inventoryReference, inventory));
        boolean overflowed = processPass(plugin, cursor);
        cursor.incrementPasses();
        if (overflowed
                || (!cursor.pending().isEmpty()
                        && cursor.passes() >= MAX_CONTINUATION_PASSES)) {
            cursors.remove(inventoryReference);
            return ScanResult.abandonedScan();
        }
        if (!cursor.pending().isEmpty()) {
            return ScanResult.continuation();
        }

        cursors.remove(inventoryReference);
        return ScanResult.complete(cursor.submitUnique(consumer));
    }

    void reset(PaperInventoryReference reference) {
        cursors.remove(Objects.requireNonNull(reference, "reference"));
    }

    void clear() {
        cursors.clear();
    }

    private ScanCursor createCursor(
            PaperInventoryReference inventoryReference,
            Inventory inventory) {
        ScanCursor cursor = new ScanCursor();
        ItemStack[] contents = Objects.requireNonNull(
                inventory.getContents(), "inventory contents");
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item != null && !item.getType().isAir()) {
                cursor.pending().add(new ScanNode(
                        PaperTemplateUpdateItemReference.root(inventoryReference, slot),
                        0));
            }
        }
        return cursor;
    }

    private boolean processPass(Plugin plugin, ScanCursor cursor) {
        int processed = 0;
        while (processed < MAX_ITEMS_PER_PASS && !cursor.pending().isEmpty()) {
            ScanNode node = cursor.pending().remove();
            Optional<PaperTemplateUpdateItemReference.Resolved> resolved =
                    node.reference().resolve(plugin);
            if (resolved.isPresent()) {
                ItemStack item = resolved.orElseThrow().originalItem();
                cursor.observe(readCandidate(item, node.reference()));
                if (node.depth() < MAX_NESTING_DEPTH
                        && enqueueChildren(cursor.pending(), node, item)) {
                    return true;
                }
            }
            processed++;
        }
        return false;
    }

    private Candidate readCandidate(
            ItemStack item,
            PaperTemplateUpdateItemReference reference) {
        ItemIdentityReadResult identity = identityCodec.readIdentity(item);
        if (identity instanceof ItemIdentityReadResult.Tracked tracked) {
            return new Candidate(tracked.identity(), reference);
        }
        return null;
    }

    private static boolean enqueueChildren(
            Queue<ScanNode> pending,
            ScanNode parent,
            ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return enqueueShulker(pending, parent, meta)
                || enqueueBundle(pending, parent, meta);
    }

    private static boolean enqueueShulker(
            Queue<ScanNode> pending,
            ScanNode parent,
            ItemMeta meta) {
        if (!(meta instanceof BlockStateMeta blockMeta)) {
            return false;
        }
        BlockState state = Objects.requireNonNull(
                blockMeta.getBlockState(), "shulker block state");
        if (!(state instanceof ShulkerBox shulker)) {
            return false;
        }
        Inventory nestedInventory = Objects.requireNonNull(
                shulker.getInventory(), "shulker inventory");
        ItemStack[] contents = Objects.requireNonNull(
                nestedInventory.getContents(), "shulker inventory contents");
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack nested = contents[slot];
            if (nested != null && !nested.getType().isAir()
                    && !enqueue(
                            pending,
                            new ScanNode(
                                    parent.reference().nested(
                                            PaperTemplateUpdateItemReference.NestedStep
                                                    .shulker(slot)),
                                    parent.depth() + 1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean enqueueBundle(
            Queue<ScanNode> pending,
            ScanNode parent,
            ItemMeta meta) {
        if (!(meta instanceof BundleMeta bundle)) {
            return false;
        }
        List<ItemStack> items = bundle.getItems();
        for (int index = 0; index < items.size(); index++) {
            ItemStack nested = items.get(index);
            if (nested != null && !nested.getType().isAir()
                    && !enqueue(
                            pending,
                            new ScanNode(
                                    parent.reference().nested(
                                            PaperTemplateUpdateItemReference.NestedStep
                                                    .bundle(index)),
                                    parent.depth() + 1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean enqueue(Queue<ScanNode> pending, ScanNode node) {
        if (pending.size() >= MAX_PENDING_REFERENCES) {
            return false;
        }
        pending.add(node);
        return true;
    }

    private record ScanNode(
            PaperTemplateUpdateItemReference reference,
            int depth) {
        private ScanNode {
            Objects.requireNonNull(reference, "reference");
            if (depth < 0 || depth > MAX_NESTING_DEPTH) {
                throw new IllegalArgumentException("Invalid scan depth");
            }
        }
    }

    private static final class ScanCursor {
        private final Queue<ScanNode> pending = new ArrayDeque<>();
        private final Map<UUID, CandidateAccumulator> candidates = new HashMap<>();
        private int passes;

        private Queue<ScanNode> pending() {
            return pending;
        }

        private int passes() {
            return passes;
        }

        private void incrementPasses() {
            passes++;
        }

        private void observe(Candidate candidate) {
            if (candidate == null) {
                return;
            }
            candidates.compute(
                    candidate.identity().instanceId().value(),
                    (ignored, accumulator) -> accumulator == null
                            ? new CandidateAccumulator(candidate)
                            : accumulator.increment());
        }

        private int submitUnique(Consumer<Candidate> consumer) {
            int submitted = 0;
            for (CandidateAccumulator accumulator : candidates.values()) {
                if (accumulator.count() == 1) {
                    consumer.accept(accumulator.first());
                    submitted++;
                }
            }
            return submitted;
        }
    }

    private static final class CandidateAccumulator {
        private final Candidate first;
        private int count = 1;

        private CandidateAccumulator(Candidate first) {
            this.first = Objects.requireNonNull(first, "first");
        }

        private CandidateAccumulator increment() {
            count++;
            return this;
        }

        private Candidate first() {
            return first;
        }

        private int count() {
            return count;
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
            boolean continuationRequired,
            boolean abandoned) {
        ScanResult {
            if (submitted < 0) {
                throw new IllegalArgumentException("submitted must not be negative");
            }
            if (abandoned && (submitted != 0 || continuationRequired)) {
                throw new IllegalArgumentException(
                        "Abandoned scans cannot submit or continue");
            }
        }

        static ScanResult complete(int submitted) {
            return new ScanResult(submitted, false, false);
        }

        static ScanResult continuation() {
            return new ScanResult(0, true, false);
        }

        static ScanResult abandonedScan() {
            return new ScanResult(0, false, true);
        }
    }
}
