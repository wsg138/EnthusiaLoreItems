package net.enthusia.loreitems.application;

import java.time.Instant;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.DeletedDefinitionMarker;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;

public interface UnitOfWork {
    <T> CompletionStage<T> execute(Work<T> work);

    @FunctionalInterface
    interface Work<T> {
        T execute(Context context) throws Exception;
    }

    interface Context {
        DefinitionMutations definitions();

        DeletedDefinitionMarkerMutations deletedDefinitionMarkers();

        AuditAppender audit();
    }

    @FunctionalInterface
    interface DefinitionMutations {
        boolean markDeleted(
                LoreDefinitionId definitionId,
                TemplateRevision expectedCurrentRevision,
                Instant deletedAt) throws Exception;
    }

    @FunctionalInterface
    interface DeletedDefinitionMarkerMutations {
        void create(DeletedDefinitionMarker marker) throws Exception;
    }

    @FunctionalInterface
    interface AuditAppender {
        AuditEventRecord append(AuditEventRecord event) throws Exception;
    }
}
