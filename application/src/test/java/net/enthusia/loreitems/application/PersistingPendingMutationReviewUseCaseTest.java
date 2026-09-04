package net.enthusia.loreitems.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
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
        assertEquals("player:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", audit.actorId());
        assertEquals(NOW.toEpochMilli(), audit.occurredAtEpochMillis());
        assertTrue(audit.detailJson().contains("physical item still has revision 1"));
    }

    @Test
    void heldItemAdoptionRetryIsRejectedBeforeDurableStateChanges() {
        AtomicInteger storeCalls = new AtomicInteger();
        PendingMutationReviewUseCase useCase = nonReplayableRetryUseCase(storeCalls);

        PendingMutationReviewUseCase.Result result = useCase.resolve(
                        nonReplayableRetryRequest(HeldItemAdoptionStore.MUTATION_TYPE))
                .toCompletableFuture()
                .join();

        assertEquals(PendingMutationReviewUseCase.Status.UNSUPPORTED_RESOLUTION, result.status());
        assertEquals(0, storeCalls.get());
        assertTrue(result.detail().contains("cannot be retried safely"));
    }

    @Test
    void voidLossRetryIsRejectedBeforeDurableStateChanges() {
        AtomicInteger storeCalls = new AtomicInteger();
        PendingMutationReviewUseCase useCase = nonReplayableRetryUseCase(storeCalls);

        PendingMutationReviewUseCase.Result result = useCase.resolve(
                        nonReplayableRetryRequest(VoidLossStore.MUTATION_TYPE))
                .toCompletableFuture()
                .join();

        assertEquals(PendingMutationReviewUseCase.Status.UNSUPPORTED_RESOLUTION, result.status());
        assertEquals(0, storeCalls.get());
        assertTrue(result.detail().contains("cancel the reviewed mutation"));
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

    @Test
    void synchronousStoreFailureIsReturnedAsFailedStage() {
        PendingMutationReviewStore store = new PendingMutationReviewStore() {
            @Override
            public CompletionStage<Status> resolve(
                    UUID mutationId,
                    String expectedMutationType,
                    Resolution resolution,
                    AuditEventRecord auditEvent,
                    Instant now) {
                throw new IllegalStateException("synchronous store failure");
            }
        };
        PendingMutationReviewUseCase useCase = new PersistingPendingMutationReviewUseCase(
                store, Clock.fixed(NOW, ZoneOffset.UTC));

        CompletableFuture<PendingMutationReviewUseCase.Result> stage = useCase.resolve(
                        new PendingMutationReviewUseCase.Request(
                                MUTATION_ID,
                                "template_update",
                                PendingMutationReviewUseCase.Resolution.RETRY,
                                "sender:CONSOLE",
                                "safe retry evidence"))
                .toCompletableFuture();

        CompletionException failure = assertThrows(CompletionException.class, stage::join);
        assertTrue(failure.getCause() instanceof IllegalStateException);
    }

    private static PendingMutationReviewUseCase nonReplayableRetryUseCase(AtomicInteger storeCalls) {
        PendingMutationReviewStore store = new PendingMutationReviewStore() {
            @Override
            public CompletionStage<Status> resolve(
                    UUID mutationId,
                    String expectedMutationType,
                    Resolution resolution,
                    AuditEventRecord auditEvent,
                    Instant now) {
                storeCalls.incrementAndGet();
                return CompletableFuture.completedFuture(Status.RETRIED);
            }
        };
        return new PersistingPendingMutationReviewUseCase(
                store, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PendingMutationReviewUseCase.Request nonReplayableRetryRequest(String mutationType) {
        return new PendingMutationReviewUseCase.Request(
                MUTATION_ID,
                mutationType,
                PendingMutationReviewUseCase.Resolution.RETRY,
                "sender:CONSOLE",
                "physical outcome is ambiguous");
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
                assertEquals("TEMPLATE_UPDATE", expectedMutationType);
                capturedAudit.set(auditEvent);
                return CompletableFuture.completedFuture(status);
            }
        };
    }
}
