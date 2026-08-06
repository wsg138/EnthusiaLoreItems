package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.domain.LoreDefinitionRevision;
import net.enthusia.loreitems.domain.TemplateRevision;

/** Durable replay key and evidence for one confirmed template edit. */
public record TemplateRevisionConfirmation(
        UUID confirmationId,
        LoreDefinitionRevision newRevision,
        TemplateRevision expectedCurrentRevision,
        EncodedItemTemplate beforeTemplate,
        AuditEventRecord auditEvent,
        UUID actorId,
        int initialBatchLimit) {
    public TemplateRevisionConfirmation {
        Objects.requireNonNull(confirmationId, "confirmationId");
        Objects.requireNonNull(newRevision, "newRevision");
        Objects.requireNonNull(expectedCurrentRevision, "expectedCurrentRevision");
        Objects.requireNonNull(beforeTemplate, "beforeTemplate");
        Objects.requireNonNull(auditEvent, "auditEvent");
        Objects.requireNonNull(actorId, "actorId");
        if (beforeTemplate.codecVersion() == newRevision.codecVersion()
                && java.util.Arrays.equals(beforeTemplate.payload(), newRevision.templateBlob())) {
            throw new IllegalArgumentException("Confirmed template must differ from before evidence");
        }
        if (!auditEvent.actorId().equals(actorId.toString())) {
            throw new IllegalArgumentException("Audit actor must match confirmation actor");
        }
        if (!newRevision.revision().equals(expectedCurrentRevision.next())) {
            throw new IllegalArgumentException("Confirmed revision must immediately follow expected revision");
        }
        if (initialBatchLimit < 1 || initialBatchLimit > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("initialBatchLimit is outside bounded page limits");
        }
    }
}
