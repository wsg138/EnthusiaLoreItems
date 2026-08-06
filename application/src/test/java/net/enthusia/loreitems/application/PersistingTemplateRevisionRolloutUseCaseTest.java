package net.enthusia.loreitems.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreDefinitionRevision;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;

class PersistingTemplateRevisionRolloutUseCaseTest {
    private static final long NOW = 4_000L;

    @Test
    void buildsTheNextImmutableRevisionAndAuditIntent() {
        CapturingStore store = new CapturingStore();
        PersistingTemplateRevisionRolloutUseCase useCase = new PersistingTemplateRevisionRolloutUseCase(
                store,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
        UUID actorId = UUID.randomUUID();
        UUID confirmationId = UUID.randomUUID();
        EncodedItemTemplate before = new EncodedItemTemplate(3, new byte[] {1, 2, 3});
        EncodedItemTemplate after = new EncodedItemTemplate(3, new byte[] {9, 8, 7});

        TemplateRevisionStartResult result = useCase.start(new TemplateRevisionRolloutRequest(
                        confirmationId,
                        definitionId,
                        new TemplateRevision(7),
                        before,
                        after,
                        actorId,
                        25))
                .toCompletableFuture().join();

        assertEquals(TemplateRevisionStartStatus.STARTED, result.status());
        assertEquals(confirmationId, store.confirmation.confirmationId());
        assertEquals(new TemplateRevision(8), store.revision.revision());
        assertEquals(3, store.revision.codecVersion());
        assertEquals(NOW, store.revision.createdAtEpochMillis());
        assertEquals("template_revision_started", store.audit.eventType());
        assertEquals(actorId.toString(), store.audit.actorId());
        assertTrue(store.audit.detailJson().contains("\"confirmationId\""));
        assertTrue(store.audit.detailJson().contains("\"previousRevision\":7"));
        assertTrue(store.audit.detailJson().contains("\"targetRevision\":8"));
        assertTrue(store.audit.detailJson().contains("\"beforeSha256\""));
        assertTrue(store.audit.detailJson().contains("\"afterSha256\""));
        assertEquals(25, store.initialBatchLimit);
    }

    @Test
    void rejectsOffsetPagingBeforeCallingStorage() {
        CapturingStore store = new CapturingStore();
        PersistingTemplateRevisionRolloutUseCase useCase = new PersistingTemplateRevisionRolloutUseCase(
                store,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

        assertThrows(
                IllegalArgumentException.class,
                () -> useCase.listIncomplete(new PageRequest(10, 10)));
        assertEquals(0, store.listCalls);
    }

    @Test
    void validatesContinuationLimitsBeforeCallingStorage() {
        CapturingStore store = new CapturingStore();
        PersistingTemplateRevisionRolloutUseCase useCase = new PersistingTemplateRevisionRolloutUseCase(
                store,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        TemplateRevisionRolloutCandidate candidate = new TemplateRevisionRolloutCandidate(
                new LoreDefinitionId(UUID.randomUUID()),
                new TemplateRevision(2));

        assertThrows(
                IllegalArgumentException.class,
                () -> useCase.scheduleNextBatch(candidate, PageRequest.MAX_LIMIT + 1));
        assertEquals(0, store.continuationCalls);
    }

    private static final class CapturingStore implements TemplateRevisionRolloutStore {
        private TemplateRevisionConfirmation confirmation;
        private LoreDefinitionRevision revision;
        private AuditEventRecord audit;
        private int initialBatchLimit;
        private int continuationCalls;
        private int listCalls;

        @Override
        public CompletionStage<TemplateRevisionStartResult> startConfirmed(
                TemplateRevisionConfirmation confirmed) {
            confirmation = confirmed;
            return start(
            confirmed.newRevision(),
            confirmed.expectedCurrentRevision(),
            confirmed.auditEvent(),
            confirmed.initialBatchLimit());
        }

        @Override
        public CompletionStage<TemplateRevisionStartResult> start(
                LoreDefinitionRevision newRevision,
                TemplateRevision expectedCurrentRevision,
                AuditEventRecord auditEvent,
                int batchLimit) {
            revision = newRevision;
            audit = auditEvent;
            initialBatchLimit = batchLimit;
            return CompletableFuture.completedFuture(TemplateRevisionStartResult.started(
                    newRevision.definitionId(),
                    newRevision.revision(),
                    TemplateRevisionRolloutBatchResult.complete(0)));
        }

        @Override
        public CompletionStage<TemplateRevisionRolloutBatchResult> scheduleNextBatch(
                TemplateRevisionRolloutCandidate candidate,
                long scheduledAtEpochMillis,
                int limit) {
            continuationCalls++;
            return CompletableFuture.completedFuture(
                    TemplateRevisionRolloutBatchResult.complete(0));
        }

        @Override
        public CompletionStage<Page<TemplateRevisionRolloutCandidate>> listIncomplete(
                PageRequest request) {
            listCalls++;
            return CompletableFuture.completedFuture(
                    new Page<>(List.of(), request.offset(), request.limit(), false));
        }
    }
}
