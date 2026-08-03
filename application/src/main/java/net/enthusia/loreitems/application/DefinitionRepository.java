package net.enthusia.loreitems.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreDefinitionRevision;
import net.enthusia.loreitems.domain.TemplateRevision;

public interface DefinitionRepository {
    CompletionStage<Void> create(
            LoreDefinition definition, LoreDefinitionRevision initialRevision);

    CompletionStage<Optional<LoreDefinition>> findById(LoreDefinitionId definitionId);

    CompletionStage<Optional<LoreDefinition>> findActiveByKey(DefinitionKey key);

    default CompletionStage<Optional<LoreDefinitionId>> findActiveIdByKey(DefinitionKey key) {
        Objects.requireNonNull(key, "key");
        return findActiveByKey(key).thenApply(definition -> definition.map(LoreDefinition::id));
    }

    CompletionStage<Optional<LoreDefinitionRevision>> findRevision(
            LoreDefinitionId definitionId, TemplateRevision revision);

    CompletionStage<Page<LoreDefinitionRevision>> listRevisions(
            LoreDefinitionId definitionId, PageRequest request);

    CompletionStage<Page<LoreDefinition>> listActive(PageRequest request);

    CompletionStage<Boolean> appendRevision(
            LoreDefinitionId definitionId,
            TemplateRevision expectedCurrentRevision,
            LoreDefinitionRevision newRevision);

    CompletionStage<Boolean> markDeleted(
            LoreDefinitionId definitionId,
            TemplateRevision expectedCurrentRevision,
            Instant deletedAt);
}
