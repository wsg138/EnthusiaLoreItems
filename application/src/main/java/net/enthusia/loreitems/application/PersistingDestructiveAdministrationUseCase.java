package net.enthusia.loreitems.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class PersistingDestructiveAdministrationUseCase
        implements DestructiveAdministrationUseCase {
    private final DestructiveOperationStore store;
    private final Clock clock;

    public PersistingDestructiveAdministrationUseCase(
            DestructiveOperationStore store,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<Optional<Preview>> preview(PreviewRequest request) {
        return store.preview(Objects.requireNonNull(request, "request"));
    }

    @Override
    public CompletionStage<StartResult> start(StartRequest request) {
        Objects.requireNonNull(request, "request");
        return store.start(request, UUID.randomUUID(), clock.instant());
    }

    @Override
    public CompletionStage<Page<OperationView>> listOperations(PageRequest request) {
        return store.listOperations(Objects.requireNonNull(request, "request"));
    }

    @Override
    public CompletionStage<Page<TargetView>> listTargets(
            UUID operationId,
            PageRequest request) {
        return store.listTargets(
                Objects.requireNonNull(operationId, "operationId"),
                Objects.requireNonNull(request, "request"));
    }

    @Override
    public CompletionStage<ControlResult> pause(ControlRequest request) {
        return store.pause(
                Objects.requireNonNull(request, "request"),
                clock.instant());
    }

    @Override
    public CompletionStage<ControlResult> resume(ControlRequest request) {
        return store.resume(
                Objects.requireNonNull(request, "request"),
                clock.instant());
    }

    @Override
    public CompletionStage<ReviewResult> resolveReview(ReviewRequest request) {
        return store.resolveReview(
                Objects.requireNonNull(request, "request"),
                clock.instant());
    }

    @Override
    public CompletionStage<Metrics> metrics() {
        Instant now = clock.instant();
        return store.metrics(now);
    }
}