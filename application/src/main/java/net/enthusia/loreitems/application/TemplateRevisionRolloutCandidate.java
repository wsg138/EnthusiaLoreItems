package net.enthusia.loreitems.application;

import java.util.Objects;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;

public record TemplateRevisionRolloutCandidate(
        LoreDefinitionId definitionId,
        TemplateRevision targetRevision) {
    public TemplateRevisionRolloutCandidate {
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(targetRevision, "targetRevision");
    }
}
