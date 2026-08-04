package net.enthusia.loreitems.application;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Shared persistence facade for anomaly evidence and ordinary physical-location tracking. */
public final class PersistingItemAnomalyObservationUseCase
        implements ItemAnomalyObservationUseCase, TrackingObservationUseCase {
    private final ItemAnomalyObservationStore anomalyStore;
    private final TrackingObservationStore trackingStore;
    private final Clock clock;
    private final Supplier<UUID> anomalyIdSupplier;

    public PersistingItemAnomalyObservationUseCase(
            ItemAnomalyObservationStore store,
            Clock clock) {
        this(store, trackingStore(store), clock, UUID::randomUUID);
    }

    PersistingItemAnomalyObservationUseCase(
            ItemAnomalyObservationStore store,
            Clock clock,
            Supplier<UUID> anomalyIdSupplier) {
        this(store, trackingStore(store), clock, anomalyIdSupplier);
    }

    PersistingItemAnomalyObservationUseCase(
            ItemAnomalyObservationStore anomalyStore,
            TrackingObservationStore trackingStore,
            Clock clock,
            Supplier<UUID> anomalyIdSupplier) {
        this.anomalyStore = Objects.requireNonNull(anomalyStore, "anomalyStore");
        this.trackingStore = Objects.requireNonNull(trackingStore, "trackingStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.anomalyIdSupplier = Objects.requireNonNull(anomalyIdSupplier, "anomalyIdSupplier");
    }

    @Override
    public CompletionStage<ItemAnomalyObservationUseCase.Result> record(
            ItemAnomalyObservationUseCase.Request request) {
        Objects.requireNonNull(request, "request");
        return anomalyStore.record(new ItemAnomalyObservationStore.Observation(
                Objects.requireNonNull(
                        anomalyIdSupplier.get(), "anomalyIdSupplier returned null"),
                request,
                clock.millis()));
    }

    @Override
    public CompletionStage<TrackingObservationUseCase.Result> record(
            TrackingObservationUseCase.Request request) {
        Objects.requireNonNull(request, "request");
        return trackingStore.record(request, clock.instant());
    }

    private static TrackingObservationStore trackingStore(ItemAnomalyObservationStore store) {
        if (store instanceof TrackingObservationStore tracking) {
            return tracking;
        }
        return (request, observedAt) -> CompletableFuture.completedFuture(
                TrackingObservationUseCase.Result.of(
                        TrackingObservationUseCase.Status.SERVICE_UNAVAILABLE,
                        "The configured anomaly store does not support physical tracking."));
    }
}
