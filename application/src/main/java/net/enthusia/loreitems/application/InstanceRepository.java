package net.enthusia.loreitems.application;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstance;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.LoreInstanceLifecycle;
import net.enthusia.loreitems.domain.TemplateRevision;

public interface InstanceRepository {
    CompletionStage<Void> create(LoreInstance instance);

    CompletionStage<Optional<LoreInstance>> findById(LoreInstanceId instanceId);

    CompletionStage<Page<LoreInstance>> listByDefinition(
            LoreDefinitionId definitionId, PageRequest request);

    CompletionStage<Boolean> compareAndSetRevisions(
            LoreInstanceId instanceId,
            TemplateRevision expectedAppliedRevision,
            TemplateRevision expectedDesiredRevision,
            TemplateRevision targetAppliedRevision,
            TemplateRevision targetDesiredRevision);

    CompletionStage<Boolean> compareAndSetLifecycle(
            LoreInstanceId instanceId,
            LoreInstanceLifecycle expected,
            LoreInstanceLifecycle target,
            Instant terminalAt);
}
