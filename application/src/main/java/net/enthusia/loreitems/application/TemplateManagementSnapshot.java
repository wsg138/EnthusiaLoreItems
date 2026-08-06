package net.enthusia.loreitems.application;

import java.util.Objects;
import net.enthusia.loreitems.domain.LoreDefinition;

/** Bounded definition-specific editor view data. */
public record TemplateManagementSnapshot(
        LoreDefinition definition,
        EncodedItemTemplate currentTemplate,
        long activeInstanceCount,
        long anomalyCount,
        long pendingUpdateCount) {
    public TemplateManagementSnapshot {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(currentTemplate, "currentTemplate");
        requireCount(activeInstanceCount, "activeInstanceCount");
        requireCount(anomalyCount, "anomalyCount");
        requireCount(pendingUpdateCount, "pendingUpdateCount");
    }

    public boolean rolloutActive() {
        return pendingUpdateCount > 0L;
    }

    private static void requireCount(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
