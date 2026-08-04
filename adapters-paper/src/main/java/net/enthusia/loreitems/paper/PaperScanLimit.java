package net.enthusia.loreitems.paper;

/** Mutable budget shared by one bounded inventory or chunk scan. */
final class PaperScanLimit {
    private int remaining;

    PaperScanLimit(int remaining) {
        if (remaining < 1) {
            throw new IllegalArgumentException("remaining must be positive");
        }
        this.remaining = remaining;
    }

    boolean hasRemaining() {
        return remaining > 0;
    }

    void consume() {
        if (remaining > 0) {
            remaining--;
        }
    }
}
