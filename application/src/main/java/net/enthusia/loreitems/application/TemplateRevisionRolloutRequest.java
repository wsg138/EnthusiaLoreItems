package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;

public record TemplateRevisionRolloutRequest(
        LoreDefinitionId definitionId,
        TemplateRevision expectedCurrentRevision,
        EncodedItemTemplate template,
        UUID actorId,
        int initialBatchLimit) {
    public TemplateRevisionRolloutRequest {
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(expectedCurrentRevision, "expectedCurrentRevision");
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(actorId, "actorId");
        if (initialBatchLimit < 1 || initialBatchLimit > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "initialBatchLimit is outside bounded page limits");
        }
    }
}
