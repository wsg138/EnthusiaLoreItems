package net.enthusia.loreitems.application;

public record CampaignCancellationResult(boolean cancelled, int recipientsCancelled) {
    public CampaignCancellationResult {
        if (recipientsCancelled < 0) {
            throw new IllegalArgumentException("recipientsCancelled must not be negative");
        }
        if (!cancelled && recipientsCancelled != 0) {
            throw new IllegalArgumentException(
                    "A failed campaign cancellation cannot report cancelled recipients");
        }
    }
}
