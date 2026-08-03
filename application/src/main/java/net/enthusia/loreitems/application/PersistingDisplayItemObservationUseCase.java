package net.enthusia.loreitems.application;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class PersistingDisplayItemObservationUseCase
        implements DisplayItemObservationUseCase {
    private final DisplayItemObservationStore store;
    private final Clock clock;

    public PersistingDisplayItemObservationUseCase(
            DisplayItemObservationStore store,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<Result> record(Request request) {
        return store.record(Objects.requireNonNull(request, "request"), clock.instant());
    }
}
