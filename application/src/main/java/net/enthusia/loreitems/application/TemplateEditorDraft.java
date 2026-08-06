package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;

/** Pure immutable editor draft metadata used to create one replay-safe confirmation request. */
public record TemplateEditorDraft(
        UUID confirmationId,
        LoreDefinitionId definitionId,
        TemplateRevision expectedCurrentRevision,
        EncodedItemTemplate beforeTemplate,
        EncodedItemTemplate draftTemplate,
        UUID actorId) {
    public TemplateEditorDraft {
        Objects.requireNonNull(confirmationId, "confirmationId");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(expectedCurrentRevision, "expectedCurrentRevision");
        Objects.requireNonNull(beforeTemplate, "beforeTemplate");
        Objects.requireNonNull(draftTemplate, "draftTemplate");
        Objects.requireNonNull(actorId, "actorId");
    }

    public static TemplateEditorDraft begin(
            UUID confirmationId,
            LoreDefinitionId definitionId,
            TemplateRevision expectedCurrentRevision,
            EncodedItemTemplate currentTemplate,
            UUID actorId) {
        return new TemplateEditorDraft(
                confirmationId,
                definitionId,
                expectedCurrentRevision,
                currentTemplate,
                currentTemplate,
                actorId);
    }

    public TemplateEditorDraft withTemplate(EncodedItemTemplate replacement) {
        return new TemplateEditorDraft(
                confirmationId,
                definitionId,
                expectedCurrentRevision,
                beforeTemplate,
                Objects.requireNonNull(replacement, "replacement"),
                actorId);
    }

    public boolean changed() {
        return !beforeTemplate.equals(draftTemplate);
    }

    public TemplateRevisionRolloutRequest confirm(int initialBatchLimit) {
        if (!changed()) {
            throw new IllegalArgumentException("An unchanged editor draft cannot be confirmed");
        }
        return new TemplateRevisionRolloutRequest(
                confirmationId,
                definitionId,
                expectedCurrentRevision,
                beforeTemplate,
                draftTemplate,
                actorId,
                initialBatchLimit);
    }
}
