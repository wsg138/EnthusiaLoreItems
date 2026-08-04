package net.enthusia.loreitems.paper;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/** Deduplicated references rejected only because the bounded scan backlog was full. */
final class PaperTemplateUpdateRetryBacklog {
    private static final int MIN_CAPACITY = 1;

    private final int capacity;
    private final Queue<PaperInventoryReference> queued = new ArrayDeque<>();
    private final Set<PaperInventoryReference> references = new HashSet<>();

    PaperTemplateUpdateRetryBacklog(int capacity) {
        if (capacity < MIN_CAPACITY) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    boolean offer(PaperInventoryReference reference) {
        Objects.requireNonNull(reference, "reference");
        if (references.contains(reference)) {
            return false;
        }
        if (queued.size() >= capacity || !references.add(reference)) {
            return false;
        }
        queued.add(reference);
        return true;
    }

    PaperInventoryReference poll() {
        PaperInventoryReference reference = queued.poll();
        if (reference != null) {
            references.remove(reference);
        }
        return reference;
    }

    void remove(PaperInventoryReference reference) {
        Objects.requireNonNull(reference, "reference");
        if (references.remove(reference)) {
            queued.remove(reference);
        }
    }

    void clear() {
        queued.clear();
        references.clear();
    }
}
