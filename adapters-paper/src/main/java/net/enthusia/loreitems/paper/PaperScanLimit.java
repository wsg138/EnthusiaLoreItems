package net.enthusia.loreitems.paper;

/** Mutable server-thread-only work budget shared by one bounded physical tracking scan. */
final class PaperScanLimit {
    private static final int EXHAUSTED = 0;
    private static final int MINIMUM = 1;

    private int remaining;
    private boolean truncated;

    PaperScanLimit(int maximum) {
        if (maximum < MINIMUM) {
            throw new IllegalArgumentException("maximum must be positive");
        }
        remaining = maximum;
    }

    /** True unless an item visit or nested-container expansion exceeded the work budget. */
    boolean hasRemaining() {
        return !truncated;
    }

    /**
     * Reserves one bounded unit before every non-air stack visit and every nested-container
     * expansion. An unvisited stack must never be interpreted as authoritative absence.
     * Recursive metadata pre-probes need separate bounds/continuation before live release.
     */
    boolean tryConsume() {
        if (remaining <= EXHAUSTED) {
            truncated = true;
            return false;
        }
        remaining--;
        return true;
    }

    void consume() {
        tryConsume();
    }

    /** A known unvisited subtree must never be considered fully observed. */
    void markTruncated() {
        truncated = true;
    }
}
