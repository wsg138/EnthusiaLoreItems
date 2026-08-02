package net.enthusia.loreitems.application;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.LoreDefinitionId;

public interface DefinitionRepository {
    CompletionStage<Optional<LoreDefinitionId>> findActiveIdByKey(DefinitionKey key);
}
