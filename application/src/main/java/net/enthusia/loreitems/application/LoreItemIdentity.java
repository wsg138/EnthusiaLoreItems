package net.enthusia.loreitems.application;

import java.util.Objects;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;

public record LoreItemIdentity(
        LoreDefinitionId definitionId,
        LoreInstanceId instanceId,
        TemplateRevision appliedRevision) {
    public LoreItemIdentity {
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(appliedRevision, "appliedRevision");
    }
}
