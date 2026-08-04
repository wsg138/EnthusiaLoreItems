package net.enthusia.loreitems.application;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionStage;

public interface TemplateUpdateExecutionStore {
    CompletionStage<TemplateUpdatePrepareResult> prepareTemplateUpdate(
            LoreItemIdentity observedIdentity,
            String claimToken,
            Instant now,
            Duration lease);

    CompletionStage<Boolean> releaseTemplateUpdate(
            PreparedTemplateUpdate update,
            String reason,
            Instant now);

    CompletionStage<Boolean> completeTemplateUpdate(
            PreparedTemplateUpdate update,
            String beforeFingerprint,
            String afterFingerprint,
            Instant now);

    CompletionStage<Boolean> requireTemplateUpdateReview(
            PreparedTemplateUpdate update,
            String reason,
            String beforeFingerprint,
            String afterFingerprint,
            Instant now);
}
