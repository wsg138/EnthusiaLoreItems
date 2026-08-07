package net.enthusia.loreitems.application;

public record CampaignRecipientCounts(
        long unresolved,
        long queuedOffline,
        long queuedInventoryFull,
        long reservedInFlight,
        long reviewRequired,
        long delivered,
        long cancelled) {
    private static final long MIN_COUNT = 0L;

    public CampaignRecipientCounts {
        requireNonNegative(unresolved, "unresolved");
        requireNonNegative(queuedOffline, "queuedOffline");
        requireNonNegative(queuedInventoryFull, "queuedInventoryFull");
        requireNonNegative(reservedInFlight, "reservedInFlight");
        requireNonNegative(reviewRequired, "reviewRequired");
        requireNonNegative(delivered, "delivered");
        requireNonNegative(cancelled, "cancelled");
    }

    public long total() {
        return Math.addExact(
                Math.addExact(
                        Math.addExact(unresolved, queuedOffline),
                        Math.addExact(queuedInventoryFull, reservedInFlight)),
                Math.addExact(
                        Math.addExact(reviewRequired, delivered),
                        cancelled));
    }

    public long remaining() {
        return Math.addExact(
                Math.addExact(unresolved, queuedOffline),
                Math.addExact(
                        Math.addExact(queuedInventoryFull, reservedInFlight),
                        reviewRequired));
    }

    /** Compatibility accessors for callers using the foundation names. */
    public long pendingName() {
        return unresolved;
    }

    public long pendingOffline() {
        return queuedOffline;
    }

    public long pendingSpace() {
        return queuedInventoryFull;
    }

    public long reserved() {
        return reservedInFlight;
    }

    private static void requireNonNegative(long value, String name) {
        if (value < MIN_COUNT) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
