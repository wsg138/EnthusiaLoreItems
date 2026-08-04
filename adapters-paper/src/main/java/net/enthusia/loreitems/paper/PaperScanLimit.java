package net.enthusia.loreitems.paper;

/** Mutable server-thread-only counter shared by one bounded scan. */
final class PaperScanLimit {
    private static final int EXHAUSTED = 0;
    private static final int MINIMUM = 1;

    private int remaining;

    PaperScanLimit(int maximum) {
        if (maximum < MINIMUM) {
            throw new IllegalArgumentException("maximum must be positive");
        }
        remaining = maximum;
    }

    boolean hasRemaining() {
        return remaining > EXHAUSTED;
    }

    void consume() {
        if (remaining > EXHAUSTED) {
            remaining--;
        }
    }
}
