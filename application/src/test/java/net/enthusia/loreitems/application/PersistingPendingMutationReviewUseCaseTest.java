package net.enthusia.loreitems.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PersistingPendingMutationReviewUseCaseTest {
    private static final UUID MUTATION_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");

    @Test
    void retryRequiresEvidenceAndCreatesStaffAuditBeforeDelegating() {
        AtomicReference<AuditEventRecord> capturedAudit = new AtomicReference<>();
        PendingMutationReviewStore store = recordingStore(
                PendingMutationReviewStore.Status.RETRIED, capturedAudit);
        PendingMutationReviewUseCase useCase = new PersistingPendingMutationReviewUseCase(
                store, Clock.fixed(NOW, ZoneOffset.UTC));

        PendingMutationReviewUseCase.Result result = useCase.resolve(
                        new PendingMutationReviewUseCase.Request(
                                MUTATION_ID,
                                "template_update",
                                PendingMutationReviewUseCase.Resolution.RETRY,
                                "player:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                                "physical item still has revision 1; safe to retry"))
                .toCompletableFuture()
                .join();

        assertEquals(PendingMutationReviewUseCase.Status.RETRIED, result.status());
        AuditEventRecord audit = capturedAudit.get();
        assertEquals("pending_mutation", audit.aggregateType());
        assertEquals(MUTATION_ID.toString(), audit.aggregateId());
        assertEquals("mutation_review_retried", audit.eventType());
        assertEquals("STAFF", audit.actorType());
        assertEquals(NOW.toEpochMilli(), audit.occurredAtEpochMillis());
        assertTrue(audit.detailJson().contains("physical item still has revision 1"));
    }

    @Test
    void cancelMapsDurableStoreStatusWithoutRetrying() {
        PendingMutationReviewUseCase useCase = new PersistingPendingMutationReviewUseCase(
                recordingStore(PendingMutationReviewStore.Status.CANCELLED, new AtomicReference<>()),
                Clock.fixed(NOW, ZoneOffset.UTC));

        PendingMutationReviewUseCase.Result result = useCase.resolve(
                        new PendingMutationReviewUseCase.Request(
                                MUTATION_ID,
                                "template_update",
                                PendingMutationReviewUseCase.Resolution.CANCEL,
                                "sender:CONSOLE",
                                "physical update already applied; cancelling prevents a blind repeat"))
                .toCompletableFuture()
                .join();

        assertEquals(PendingMutationReviewUseCase.Status.CANCELLED, result.status());
    }

    private static PendingMutationReviewStore recordingStore(
            PendingMutationReviewStore.Status status,
            AtomicReference<AuditEventRecord> capturedAudit) {
        return new PendingMutationReviewStore() {
            @Override
            public CompletionStage<Status> resolve(
                    UUID mutationId,
                    String expectedMutationType,
                    Resolution resolution,
                    AuditEventRecord auditEvent,
                    Instant now) {
                assertEquals(MUTATION_ID, mutationId);
                assertEquals("template_update", expectedMutationType);
                capturedAudit.set(auditEvent);
                return CompletableFuture.completedFuture(status);
            }
        };
    }
}
