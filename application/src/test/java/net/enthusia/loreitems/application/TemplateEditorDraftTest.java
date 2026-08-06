package net.enthusia.loreitems.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;

class TemplateEditorDraftTest {
    @Test
    void beginsUnchangedAndRejectsConfirmationUntilAValidatedReplacementExists() {
        TemplateEditorDraft draft = TemplateEditorDraft.begin(
                UUID.randomUUID(),
                new LoreDefinitionId(UUID.randomUUID()),
                new TemplateRevision(4),
                template(1),
                UUID.randomUUID());

        assertFalse(draft.changed());
        assertThrows(IllegalArgumentException.class, () -> draft.confirm(8));
    }

    @Test
    void replacementPreservesBeforeEvidenceAndBuildsOneReplaySafeRequest() {
        UUID confirmationId = UUID.randomUUID();
        LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
        TemplateRevision currentRevision = new TemplateRevision(4);
        EncodedItemTemplate before = template(1);
        EncodedItemTemplate after = template(2);
        UUID actorId = UUID.randomUUID();
        TemplateEditorDraft original = TemplateEditorDraft.begin(
                confirmationId, definitionId, currentRevision, before, actorId);

        TemplateEditorDraft changed = original.withTemplate(after);
        TemplateRevisionRolloutRequest request = changed.confirm(8);

        assertFalse(original.changed());
        assertTrue(changed.changed());
        assertEquals(before, changed.beforeTemplate());
        assertEquals(after, changed.draftTemplate());
        assertEquals(confirmationId, request.confirmationId());
        assertEquals(definitionId, request.definitionId());
        assertEquals(currentRevision, request.expectedCurrentRevision());
        assertEquals(before, request.beforeTemplate());
        assertEquals(after, request.template());
        assertEquals(actorId, request.actorId());
        assertEquals(8, request.initialBatchLimit());
        assertEquals(request, changed.confirm(8));
    }

    @Test
    void changingBackToTheOriginalTemplateRestoresUnchangedState() {
        EncodedItemTemplate before = template(1);
        TemplateEditorDraft draft = TemplateEditorDraft.begin(
                UUID.randomUUID(),
                new LoreDefinitionId(UUID.randomUUID()),
                new TemplateRevision(1),
                before,
                UUID.randomUUID());

        TemplateEditorDraft reverted = draft.withTemplate(template(2)).withTemplate(before);

        assertFalse(reverted.changed());
        assertNotEquals(template(2), reverted.draftTemplate());
        assertThrows(IllegalArgumentException.class, () -> reverted.confirm(1));
    }

    @Test
    void confirmationRetainsBoundedBatchValidation() {
        TemplateEditorDraft draft = TemplateEditorDraft.begin(
                UUID.randomUUID(),
                new LoreDefinitionId(UUID.randomUUID()),
                new TemplateRevision(1),
                template(1),
                UUID.randomUUID()).withTemplate(template(2));

        assertThrows(IllegalArgumentException.class, () -> draft.confirm(0));
        assertThrows(IllegalArgumentException.class,
                () -> draft.confirm(PageRequest.MAX_LIMIT + 1));
    }

    private static EncodedItemTemplate template(int marker) {
        return new EncodedItemTemplate(1, new byte[] {(byte) marker});
    }
}
