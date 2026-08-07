package net.enthusia.loreitems.paper;

import java.util.UUID;

public interface DistributionCancellationFence {
    void begin(UUID campaignId);

    void committed(UUID campaignId);

    void release(UUID campaignId);
}
