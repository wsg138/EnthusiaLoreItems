package net.enthusia.loreitems.application;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class PersistingTrackingObservationUseCase implements TrackingObservationUseCase {
    private final TrackingObservationStore store;
    private final Clock clock;

    public PersistingTrackingObservationUseCase(TrackingObservationStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<Result> record(Request request) {
        Objects.requireNonNull(request, "request");
        return store.record(request, clock.instant());
    }
}
