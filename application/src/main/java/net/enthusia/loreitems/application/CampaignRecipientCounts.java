package net.enthusia.loreitems.application;

public record CampaignRecipientCounts(
        long pendingName,
        long pendingOffline,
        long pendingSpace,
        long reserved,
        long delivered,
        long cancelled,
        long reviewRequired) {
    public CampaignRecipientCounts {
        requireNonNegative(pendingName, "pendingName");
        requireNonNegative(pendingOffline, "pendingOffline");
        requireNonNegative(pendingSpace, "pendingSpace");
        requireNonNegative(reserved, "reserved");
        requireNonNegative(delivered, "delivered");
        requireNonNegative(cancelled, "cancelled");
        requireNonNegative(reviewRequired, "reviewRequired");
    }

    public long total() {
        return Math.addExact(
                Math.addExact(
                        Math.addExact(pendingName, pendingOffline),
                        Math.addExact(pendingSpace, reserved)),
                Math.addExact(
                        Math.addExact(delivered, cancelled),
                        reviewRequired));
    }

    public long remaining() {
        return Math.addExact(
                Math.addExact(pendingName, pendingOffline),
                Math.addExact(
                        Math.addExact(pendingSpace, reserved),
                        reviewRequired));
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
