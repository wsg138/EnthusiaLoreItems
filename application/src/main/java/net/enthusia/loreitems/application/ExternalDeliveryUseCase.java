package net.enthusia.loreitems.application;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ExternalDeliveryUseCase {
    CompletionStage<ExternalDeliveryOutcome> queue(ExternalDeliveryCommand command);
}
