package net.enthusia.loreitems.paper;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/** Two-tier bounded FIFO that retains transient scan bursts without unbounded allocation. */
final class PaperTemplateUpdateScanBacklog {
    private static final int MIN_TIER_CAPACITY = 1;

    enum OfferResult {
        READY,
        DEFERRED,
        ALREADY_QUEUED,
        REJECTED
    }

    private final int tierCapacity;
    private final Queue<PaperInventoryReference> ready = new ArrayDeque<>();
    private final Queue<PaperInventoryReference> deferred = new ArrayDeque<>();
    private final Set<PaperInventoryReference> queued = new HashSet<>();

    PaperTemplateUpdateScanBacklog(int tierCapacity) {
        if (tierCapacity < MIN_TIER_CAPACITY) {
            throw new IllegalArgumentException("tierCapacity must be positive");
        }
        this.tierCapacity = tierCapacity;
    }

    OfferResult offer(PaperInventoryReference reference) {
        Objects.requireNonNull(reference, "reference");
        if (!queued.add(reference)) {
            return OfferResult.ALREADY_QUEUED;
        }
        if (ready.size() < tierCapacity) {
            ready.add(reference);
            return OfferResult.READY;
        }
        if (deferred.size() < tierCapacity) {
            deferred.add(reference);
            return OfferResult.DEFERRED;
        }
        queued.remove(reference);
        return OfferResult.REJECTED;
    }

    PaperInventoryReference poll() {
        PaperInventoryReference reference = ready.poll();
        if (reference == null) {
            return null;
        }
        queued.remove(reference);
        promoteDeferred();
        return reference;
    }

    void remove(PaperInventoryReference reference) {
        Objects.requireNonNull(reference, "reference");
        boolean removed = ready.remove(reference);
        removed = deferred.remove(reference) || removed;
        queued.remove(reference);
        if (removed) {
            promoteDeferred();
        }
    }

    boolean isEmpty() {
        return ready.isEmpty() && deferred.isEmpty();
    }

    void clear() {
        ready.clear();
        deferred.clear();
        queued.clear();
    }

    private void promoteDeferred() {
        while (ready.size() < tierCapacity && !deferred.isEmpty()) {
            ready.add(deferred.remove());
        }
    }
}
