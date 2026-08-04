package net.enthusia.loreitems.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.LocationDescriptor;

/** Application-layer validation and clock ownership for anomaly evidence writes. */
public final class PersistingItemAnomalyObservationUseCase
        implements TrackingObservationUseCase {
    private final ItemAnomalyObservationStore store;
    private final TrackingObservationStore physicalTrackingStore;
    private final AnomalyWarningSink warningSink;
    private final Clock clock;

    public PersistingItemAnomalyObservationUseCase(
            ItemAnomalyObservationStore store,
            AnomalyWarningSink warningSink,
            Clock clock) {
        this(store, resolveTrackingStore(store), warningSink, clock);
    }

    public PersistingItemAnomalyObservationUseCase(
            ItemAnomalyObservationStore store,
            TrackingObservationStore trackingStore,
            AnomalyWarningSink warningSink,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.physicalTrackingStore = Objects.requireNonNull(trackingStore, "trackingStore");
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<TrackingObservationUseCase.Result> record(
            TrackingObservationUseCase.Request request) {
        Objects.requireNonNull(request, "request");
        return Objects.requireNonNull(
                physicalTrackingStore.record(request, clock.instant()),
                "tracking observation stage");
    }

    @Override
    public CompletionStage<ItemAnomalyObservationUseCase.Result> observe(
            ItemAnomalyObservationUseCase.Request request) {
        Objects.requireNonNull(request, "request");
        Instant observedAt = clock.instant();
        CompletionStage<ItemAnomalyObservationUseCase.Result> stage = switch (request) {
            case ItemAnomalyObservationUseCase.Request.Duplicate duplicate -> store
                    .recordDuplicate(
                            new ItemAnomalyObservationStore.DuplicateEvidence(
                                    duplicate.identity(),
                                    duplicate.firstLocation(),
                                    duplicate.secondLocation()),
                            observedAt);
            case ItemAnomalyObservationUseCase.Request.Malformed malformed -> store
                    .recordMalformed(
                            new ItemAnomalyObservationStore.MalformedEvidence(
                                    malformed.definitionId(),
                                    malformed.instanceId(),
                                    malformed.location(),
                                    malformed.detail()),
                            observedAt);
        };
        return Objects.requireNonNull(stage, "anomaly observation stage")
                .thenApply(result -> {
                    ItemAnomalyObservationUseCase.Result normalized = Objects.requireNonNull(
                            result, "anomaly observation result");
                    if (normalized.status()
                            == ItemAnomalyObservationUseCase.Status.RECORDED_NEW) {
                        warningSink.requestWarning();
                    }
                    return normalized;
                });
    }

    private static TrackingObservationStore resolveTrackingStore(
            ItemAnomalyObservationStore store) {
        Objects.requireNonNull(store, "store");
        if (store instanceof TrackingObservationStore tracking) {
            return tracking;
        }
        return (request, observedAt) -> java.util.concurrent.CompletableFuture.completedFuture(
                TrackingObservationUseCase.Result.of(
                        TrackingObservationUseCase.Status.UNAVAILABLE,
                        "Physical tracking is not available from this anomaly store."));
    }

    /** Convenience builder for locations discovered by bounded event scans. */
    public static LocationDescriptor location(
            LocationDescriptor.Type type,
            String locationKey,
            String containerPath) {
        return new LocationDescriptor(type, locationKey, containerPath);
    }

    /** Convenience builder for optional recoverable instance evidence. */
    public static UUID optionalInstanceId(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
