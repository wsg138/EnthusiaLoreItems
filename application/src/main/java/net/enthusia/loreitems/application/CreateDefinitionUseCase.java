package net.enthusia.loreitems.application;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface CreateDefinitionUseCase {
    CompletionStage<CreateDefinitionResult> create(CreateDefinitionRequest request);
}
