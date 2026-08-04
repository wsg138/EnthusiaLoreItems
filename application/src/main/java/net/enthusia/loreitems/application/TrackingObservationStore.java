package net.enthusia.loreitems.application;

import java.time.Instant;
import java.util.concurrent.CompletionStage;

public interface TrackingObservationStore {
    CompletionStage<TrackingObservationUseCase.Result> record(
            TrackingObservationUseCase.Request request,
            Instant observedAt);
}
