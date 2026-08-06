package net.enthusia.loreitems.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ControlRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ControlResult;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.Metrics;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.OperationView;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.Preview;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.PreviewRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ReviewRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ReviewResult;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.StartRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.StartResult;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.TargetView;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase.Observation;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase.PrepareResult;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase.PreparedRemoval;
import net.enthusia.loreitems.domain.DestructiveEffectState;

public interface DestructiveOperationStore {
    CompletionStage<Optional<Preview>> preview(PreviewRequest request);

    CompletionStage<StartResult> start(
            StartRequest request, UUID operationId, Instant now);

    CompletionStage<Page<OperationView>> listOperations(PageRequest request);

    CompletionStage<Page<TargetView>> listTargets(UUID operationId, PageRequest request);

    CompletionStage<ControlResult> pause(ControlRequest request, Instant now);

    CompletionStage<ControlResult> resume(ControlRequest request, Instant now);

    CompletionStage<ReviewResult> resolveReview(ReviewRequest request, Instant now);

    CompletionStage<Metrics> metrics(Instant now);

    CompletionStage<PrepareResult> prepareRemoval(
            Observation observation,
            String claimToken,
            Instant now,
            Duration lease);

    CompletionStage<Boolean> releaseRemoval(
            PreparedRemoval removal,
            String reason,
            Instant now);

    CompletionStage<Boolean> completeRemoval(
            PreparedRemoval removal,
            String beforeFingerprint,
            Instant now);

    CompletionStage<Boolean> requireRemovalReview(
            PreparedRemoval removal,
            DestructiveEffectState effectState,
            String beforeFingerprint,
            String afterFingerprint,
            String detail,
            Instant now);

    CompletionStage<Integer> moveExpiredClaimsToReview(Instant now, int limit);
}