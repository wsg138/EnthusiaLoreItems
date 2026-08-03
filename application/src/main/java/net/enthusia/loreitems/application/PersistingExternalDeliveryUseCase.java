package net.enthusia.loreitems.application;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class PersistingExternalDeliveryUseCase implements ExternalDeliveryUseCase {
    private final DirectDeliveryRepository repository;
    private final Clock clock;

    public PersistingExternalDeliveryUseCase(DirectDeliveryRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<ExternalDeliveryOutcome> queue(ExternalDeliveryCommand command) {
        Objects.requireNonNull(command, "command");
        return repository.acceptExternal(command, clock.instant())
                .thenApply(ExternalDeliveryAcceptance::outcome);
    }
}
