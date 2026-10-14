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

    /** Legacy completeness marker. Not a cursor or a guarantee of full traversal. */
    boolean hasRemaining() {
        return !truncated;
    }

    /** Reserve one work unit. Every inspected slot (including air) must charge a unit. */
    boolean tryConsume() {
        if (remaining <= EXHAUSTED) {
            truncated = true;
            return false;
        }
        remaining--;
        return true;
    }

    boolean canVisit() {
        return remaining > EXHAUSTED;
    }

    void markTruncated() {
        truncated = true;
    }

    void consume() {
        tryConsume();
    }
}
