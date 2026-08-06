package net.enthusia.loreitems.application;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.LoreDefinitionId;

public interface TemplateManagementUseCase {
    CompletionStage<Optional<TemplateManagementSnapshot>> findSnapshot(
            LoreDefinitionId definitionId);

    CompletionStage<TemplateRevisionStartResult> confirm(TemplateRevisionRolloutRequest request);
}
