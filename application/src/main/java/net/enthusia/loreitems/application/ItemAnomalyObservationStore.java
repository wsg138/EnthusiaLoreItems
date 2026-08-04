package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface ItemAnomalyObservationStore {
    long MIN_EPOCH_MILLIS = 0L;

    CompletionStage<ItemAnomalyObservationUseCase.Result> record(Observation observation);

    record Observation(
            UUID anomalyId,
            ItemAnomalyObservationUseCase.Request request,
            long observedAtEpochMillis) {
        public Observation {
            Objects.requireNonNull(anomalyId, "anomalyId");
            Objects.requireNonNull(request, "request");
            if (observedAtEpochMillis < MIN_EPOCH_MILLIS) {
                throw new IllegalArgumentException("observedAtEpochMillis must not be negative");
            }
        }
    }
}
