package net.enthusia.loreitems.application;

import java.time.Instant;
import java.util.concurrent.CompletionStage;

public interface DisplayItemObservationStore {
    CompletionStage<DisplayItemObservationUseCase.Result> record(
            DisplayItemObservationUseCase.Request request,
            Instant observedAt);
}
