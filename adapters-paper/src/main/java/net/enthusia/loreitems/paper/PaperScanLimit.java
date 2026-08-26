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

    /** True unless a scannable root or nested-container expansion was refused by the budget. */
    boolean hasRemaining() {
        return !truncated;
    }

    /**
     * Reserves one bounded work unit. Root items/entities and nested-container expansions spend
     * units; ordinary nested leaf stacks do not, so dense container contents cannot starve later
     * root locations while pathological root/entity counts and recursive nesting remain bounded.
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
