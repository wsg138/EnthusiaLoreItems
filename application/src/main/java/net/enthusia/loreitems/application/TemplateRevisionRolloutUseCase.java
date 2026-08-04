package net.enthusia.loreitems.application;

import java.util.concurrent.CompletionStage;

public interface TemplateRevisionRolloutUseCase {
    CompletionStage<TemplateRevisionStartResult> start(
            TemplateRevisionRolloutRequest request);

    CompletionStage<TemplateRevisionRolloutBatchResult> scheduleNextBatch(
            TemplateRevisionRolloutCandidate candidate,
            int limit);

    CompletionStage<Page<TemplateRevisionRolloutCandidate>> listIncomplete(
            PageRequest request);
}
