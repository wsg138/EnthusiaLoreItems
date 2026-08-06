package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.LoreDefinitionId;

public final class PersistingTemplateManagementUseCase implements TemplateManagementUseCase {
    private final TemplateManagementQueryStore queryStore;
    private final TemplateRevisionRolloutUseCase rolloutUseCase;

    public PersistingTemplateManagementUseCase(
            TemplateManagementQueryStore queryStore,
            TemplateRevisionRolloutUseCase rolloutUseCase) {
        this.queryStore = Objects.requireNonNull(queryStore, "queryStore");
        this.rolloutUseCase = Objects.requireNonNull(rolloutUseCase, "rolloutUseCase");
    }

    @Override
    public CompletionStage<Optional<TemplateManagementSnapshot>> findSnapshot(
            LoreDefinitionId definitionId) {
        Objects.requireNonNull(definitionId, "definitionId");
        return queryStore.findSnapshot(definitionId);
    }

    @Override
    public CompletionStage<TemplateRevisionStartResult> confirm(
            TemplateRevisionRolloutRequest request) {
        Objects.requireNonNull(request, "request");
        return rolloutUseCase.start(request);
    }
}
