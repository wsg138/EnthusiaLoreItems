package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;

public record TemplateRevisionRolloutRequest(
        UUID confirmationId,
        LoreDefinitionId definitionId,
        TemplateRevision expectedCurrentRevision,
        EncodedItemTemplate beforeTemplate,
        EncodedItemTemplate template,
        UUID actorId,
        int initialBatchLimit) {
    public TemplateRevisionRolloutRequest {
        Objects.requireNonNull(confirmationId, "confirmationId");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(expectedCurrentRevision, "expectedCurrentRevision");
        Objects.requireNonNull(beforeTemplate, "beforeTemplate");
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(actorId, "actorId");
        if (initialBatchLimit < 1 || initialBatchLimit > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "initialBatchLimit is outside bounded page limits");
        }
        if (beforeTemplate.equals(template)) {
            throw new IllegalArgumentException("A confirmed edit must change the template");
        }
    }

}
