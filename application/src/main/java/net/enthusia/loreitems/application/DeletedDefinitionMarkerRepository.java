package net.enthusia.loreitems.application;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.DeletedDefinitionMarker;
import net.enthusia.loreitems.domain.LoreDefinitionId;

public interface DeletedDefinitionMarkerRepository {
    CompletionStage<Void> create(DeletedDefinitionMarker marker);

    CompletionStage<Optional<DeletedDefinitionMarker>> findByDefinitionId(
            LoreDefinitionId definitionId);

    CompletionStage<Page<DeletedDefinitionMarker>> listRecent(PageRequest request);

    CompletionStage<Page<DeletedDefinitionMarker>> listByLookupKey(
            DefinitionKey lookupKey, PageRequest request);
}
