package net.enthusia.loreitems.application;

public record DistributionRecipientBindingBatch(
        int matched,
        int bound,
        int notBound,
        boolean hasMore) {
    public DistributionRecipientBindingBatch {
        if (matched < 0 || bound < 0 || notBound < 0 || bound + notBound != matched) {
            throw new IllegalArgumentException("Invalid recipient binding counts");
        }
    }
}
