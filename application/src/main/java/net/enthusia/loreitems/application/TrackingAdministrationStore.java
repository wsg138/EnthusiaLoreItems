package net.enthusia.loreitems.application;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstance;

public interface TrackingAdministrationStore {
    CompletionStage<Page<LoreDefinition>> listDefinitions(PageRequest request);

    CompletionStage<Page<LoreInstance>> listInstances(
            LoreDefinitionId definitionId, PageRequest request);

    CompletionStage<LoreItemsAdministrationUseCase.DuplicateResolutionResult> resolveDuplicate(
            LoreItemsAdministrationUseCase.DuplicateResolutionRequest request,
            Instant resolvedAt);
}
