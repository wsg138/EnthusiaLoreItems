package net.enthusia.loreitems.paper;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/** Two-tier bounded FIFO that retains transient scan bursts without unbounded allocation. */
final class PaperTemplateUpdateScanBacklog {
    private static final int MIN_TIER_CAPACITY = 1;
    private static final int TIER_COUNT = 2;

    private final int readyCapacity;
    private final int totalCapacity;
    private final Queue<PaperInventoryReference> queue = new ArrayDeque<>();
    private final Set<PaperInventoryReference> queued = new HashSet<>();

    PaperTemplateUpdateScanBacklog(int tierCapacity) {
        if (tierCapacity < MIN_TIER_CAPACITY) {
            throw new IllegalArgumentException("tierCapacity must be positive");
        }
        this.readyCapacity = tierCapacity;
        this.totalCapacity = Math.multiplyExact(tierCapacity, TIER_COUNT);
    }

    PaperTemplateUpdateScanOfferResult offer(PaperInventoryReference reference) {
        Objects.requireNonNull(reference, "reference");
        if (!queued.add(reference)) {
            return PaperTemplateUpdateScanOfferResult.ALREADY_QUEUED;
        }
        int position = queue.size();
        if (position >= totalCapacity) {
            queued.remove(reference);
            return PaperTemplateUpdateScanOfferResult.REJECTED;
        }
        queue.add(reference);
        return position < readyCapacity
                ? PaperTemplateUpdateScanOfferResult.READY
                : PaperTemplateUpdateScanOfferResult.DEFERRED;
    }

    PaperInventoryReference poll() {
        PaperInventoryReference reference = queue.poll();
        if (reference != null) {
            queued.remove(reference);
        }
        return reference;
    }

    void remove(PaperInventoryReference reference) {
        Objects.requireNonNull(reference, "reference");
        if (queued.remove(reference)) {
            queue.remove(reference);
        }
    }

    boolean isEmpty() {
        return queue.isEmpty();
    }

    void clear() {
        queue.clear();
        queued.clear();
    }
}
