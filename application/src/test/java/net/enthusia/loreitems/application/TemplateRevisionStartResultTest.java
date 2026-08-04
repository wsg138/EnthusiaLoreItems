package net.enthusia.loreitems.application;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;

class TemplateRevisionStartResultTest {
    @Test
    void rejectsARejectedInitialBatchForAStartedRevision() {
        LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
        TemplateRevisionRolloutBatchResult rejectedBatch =
                TemplateRevisionRolloutBatchResult.rejected(
                        TemplateRevisionRolloutBatchStatus.DEFINITION_NOT_FOUND);

        assertThrows(
                IllegalArgumentException.class,
                () -> TemplateRevisionStartResult.started(
                        definitionId, new TemplateRevision(2), rejectedBatch));
    }
}
