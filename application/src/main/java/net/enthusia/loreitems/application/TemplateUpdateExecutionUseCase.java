package net.enthusia.loreitems.application;

import java.util.concurrent.CompletionStage;

public interface TemplateUpdateExecutionUseCase {
    CompletionStage<TemplateUpdatePrepareResult> prepare(LoreItemIdentity observedIdentity);

    CompletionStage<Boolean> release(PreparedTemplateUpdate update, String reason);

    CompletionStage<Boolean> complete(
            PreparedTemplateUpdate update,
            String beforeFingerprint,
            String afterFingerprint);

    CompletionStage<Boolean> requireReview(
            PreparedTemplateUpdate update,
            String reason,
            String beforeFingerprint,
            String afterFingerprint);
}
