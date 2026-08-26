package net.enthusia.loreitems.paper;

/** Mutable server-thread-only nested-container expansion budget shared by one bounded scan. */
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

    /** True unless a nested-container expansion was actually refused by the budget. */
    boolean hasRemaining() {
        return !truncated;
    }

    /**
     * Reserves one nested-container expansion. Direct item identity inspection is not charged
     * against this budget so later root slots cannot be permanently starved by ordinary leaves.
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
}
