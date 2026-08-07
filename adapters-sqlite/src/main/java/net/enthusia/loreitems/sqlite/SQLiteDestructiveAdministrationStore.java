package net.enthusia.loreitems.sqlite;

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
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.DestructiveOperationState;

final class SQLiteDestructiveAdministrationStore {
    private final SQLiteDestructiveQueryStore queries;
    private final SQLiteDestructiveAcceptanceStore acceptance;
    private final SQLiteDestructiveControlStore controls;

    SQLiteDestructiveAdministrationStore(SQLiteStorageRuntime storage) {
        Objects.requireNonNull(storage, "storage");
        this.queries = new SQLiteDestructiveQueryStore(storage);
        this.acceptance = new SQLiteDestructiveAcceptanceStore(storage, queries);
        this.controls = new SQLiteDestructiveControlStore(storage, queries);
    }

    CompletionStage<Optional<Preview>> preview(PreviewRequest request) {
        return queries.preview(request);
    }

    CompletionStage<StartResult> start(
            StartRequest request,
            UUID operationId,
            Instant now) {
        return acceptance.start(request, operationId, now);
    }

    CompletionStage<Page<OperationView>> listOperations(PageRequest request) {
        return queries.listOperations(request);
    }

    CompletionStage<Page<TargetView>> listTargets(UUID operationId, PageRequest request) {
        return queries.listTargets(operationId, request);
    }

    CompletionStage<ControlResult> pause(ControlRequest request, Instant now) {
        return controls.control(request, DestructiveOperationState.PAUSED, now);
    }

    CompletionStage<ControlResult> resume(ControlRequest request, Instant now) {
        return controls.control(request, DestructiveOperationState.ACTIVE, now);
    }

    CompletionStage<ReviewResult> resolveReview(ReviewRequest request, Instant now) {
        return controls.resolveReview(request, now);
    }

    CompletionStage<Metrics> metrics(Instant now) {
        return queries.metrics(now);
    }
}