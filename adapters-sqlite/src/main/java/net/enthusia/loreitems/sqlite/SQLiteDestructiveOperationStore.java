package net.enthusia.loreitems.sqlite;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
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
import net.enthusia.loreitems.application.DestructiveOperationStore;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase.Observation;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase.PrepareResult;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase.PreparedRemoval;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.DestructiveEffectState;

public final class SQLiteDestructiveOperationStore implements DestructiveOperationStore {
    private final SQLiteDestructiveAdministrationStore administration;
    private final SQLiteDestructiveExecutionStore execution;

    public SQLiteDestructiveOperationStore(SQLiteStorageRuntime storage) {
        Objects.requireNonNull(storage, "storage");
        this.administration = new SQLiteDestructiveAdministrationStore(storage);
        this.execution = new SQLiteDestructiveExecutionStore(storage);
    }

    @Override
    public CompletionStage<Optional<Preview>> preview(PreviewRequest request) {
        return administration.preview(request);
    }

    @Override
    public CompletionStage<StartResult> start(
            StartRequest request,
            UUID operationId,
            Instant now) {
        return administration.start(request, operationId, now);
    }

    @Override
    public CompletionStage<Page<OperationView>> listOperations(PageRequest request) {
        return administration.listOperations(request);
    }

    @Override
    public CompletionStage<Page<TargetView>> listTargets(
            UUID operationId,
            PageRequest request) {
        return administration.listTargets(operationId, request);
    }

    @Override
    public CompletionStage<ControlResult> pause(ControlRequest request, Instant now) {
        return administration.pause(request, now);
    }

    @Override
    public CompletionStage<ControlResult> resume(ControlRequest request, Instant now) {
        return administration.resume(request, now);
    }

    @Override
    public CompletionStage<ReviewResult> resolveReview(
            ReviewRequest request,
            Instant now) {
        return administration.resolveReview(request, now);
    }

    @Override
    public CompletionStage<Metrics> metrics(Instant now) {
        return administration.metrics(now);
    }

    @Override
    public CompletionStage<PrepareResult> prepareRemoval(
            Observation observation,
            String claimToken,
            Instant now,
            Duration lease) {
        return execution.prepareRemoval(observation, claimToken, now, lease);
    }

    @Override
    public CompletionStage<Boolean> releaseRemoval(
            PreparedRemoval removal,
            String reason,
            Instant now) {
        return execution.releaseRemoval(removal, reason, now);
    }

    @Override
    public CompletionStage<Boolean> completeRemoval(
            PreparedRemoval removal,
            String beforeFingerprint,
            Instant now) {
        return execution.completeRemoval(removal, beforeFingerprint, now);
    }

    @Override
    public CompletionStage<Boolean> requireRemovalReview(
            PreparedRemoval removal,
            DestructiveEffectState effectState,
            String beforeFingerprint,
            String afterFingerprint,
            String detail,
            Instant now) {
        return execution.requireRemovalReview(
                removal,
                effectState,
                beforeFingerprint,
                afterFingerprint,
                detail,
                now);
    }

    @Override
    public CompletionStage<Integer> moveExpiredClaimsToReview(Instant now, int limit) {
        return execution.moveExpiredClaimsToReview(now, limit);
    }
}