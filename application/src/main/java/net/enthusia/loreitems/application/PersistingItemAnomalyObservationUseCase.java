package net.enthusia.loreitems.application;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class PersistingItemAnomalyObservationUseCase
        implements ItemAnomalyObservationUseCase {
    private final ItemAnomalyObservationStore store;
    private final Clock clock;
    private final Supplier<UUID> anomalyIdSupplier;

    public PersistingItemAnomalyObservationUseCase(
            ItemAnomalyObservationStore store,
            Clock clock) {
        this(store, clock, UUID::randomUUID);
    }

    PersistingItemAnomalyObservationUseCase(
            ItemAnomalyObservationStore store,
            Clock clock,
            Supplier<UUID> anomalyIdSupplier) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.anomalyIdSupplier = Objects.requireNonNull(anomalyIdSupplier, "anomalyIdSupplier");
    }

    @Override
    public CompletionStage<Result> record(Request request) {
        Objects.requireNonNull(request, "request");
        return store.record(new ItemAnomalyObservationStore.Observation(
                Objects.requireNonNull(
                        anomalyIdSupplier.get(), "anomalyIdSupplier returned null"),
                request,
                clock.millis()));
    }
}
