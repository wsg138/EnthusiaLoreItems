package net.enthusia.loreitems.application;

import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.LoreDefinitionRevision;
import net.enthusia.loreitems.domain.TemplateRevision;

public interface TemplateRevisionRolloutStore {
    default CompletionStage<TemplateRevisionStartResult> startConfirmed(
            TemplateRevisionConfirmation confirmation) {
        return start(
                confirmation.newRevision(),
                confirmation.expectedCurrentRevision(),
                confirmation.auditEvent(),
                confirmation.initialBatchLimit());
    }

    CompletionStage<TemplateRevisionStartResult> start(
            LoreDefinitionRevision newRevision,
            TemplateRevision expectedCurrentRevision,
            AuditEventRecord auditEvent,
            int initialBatchLimit);

    CompletionStage<TemplateRevisionRolloutBatchResult> scheduleNextBatch(
            TemplateRevisionRolloutCandidate candidate,
            long scheduledAtEpochMillis,
            int limit);

    CompletionStage<Page<TemplateRevisionRolloutCandidate>> listIncomplete(
            PageRequest request);
}
