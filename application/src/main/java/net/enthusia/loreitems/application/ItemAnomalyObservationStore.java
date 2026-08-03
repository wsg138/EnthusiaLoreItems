package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface ItemAnomalyObservationStore {
    CompletionStage<ItemAnomalyObservationUseCase.Result> record(Observation observation);

    record Observation(
            UUID anomalyId,
            ItemAnomalyObservationUseCase.Request request,
            long observedAtEpochMillis) {
        public Observation {
            Objects.requireNonNull(anomalyId, "anomalyId");
            Objects.requireNonNull(request, "request");
            if (observedAtEpochMillis < 0L) {
                throw new IllegalArgumentException("observedAtEpochMillis must not be negative");
            }
        }
    }
}
