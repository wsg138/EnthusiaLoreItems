package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.PendingMutationRecord;
import net.enthusia.loreitems.application.PendingMutationReviewStore;
import net.enthusia.loreitems.domain.PendingMutationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLitePendingMutationReviewStoreTest {
    private static final String TEMPLATE_UPDATE = "TEMPLATE_UPDATE";

    @TempDir
    Path temporaryDirectory;

    @Test
    void retryIsAuditBackedAndImmediatelyClaimable() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("retry.db"));
        try {
            SQLitePendingMutationRepository repository =
                    new SQLitePendingMutationRepository(runtime);
            SQLitePendingMutationReviewStore reviewStore =
                    new SQLitePendingMutationReviewStore(runtime);
            UUID mutationId = seedReviewRequired(repository, 1_000L);
            Instant now = Instant.ofEpochMilli(5_000L);

            PendingMutationReviewStore.Status status = reviewStore.resolve(
                            mutationId,
                            TEMPLATE_UPDATE,
                            PendingMutationReviewStore.Resolution.RETRY,
                            audit(mutationId, "mutation_review_retried", now),
                            now)
                    .toCompletableFuture().join();
            Page<PendingMutationRecord> claimed = repository.claimPending(
                            TEMPLATE_UPDATE,
                            "retry-worker",
                            now,
                            Duration.ofSeconds(30),
                            10)
                    .toCompletableFuture().join();

            assertEquals(PendingMutationReviewStore.Status.RETRIED, status);
            assertEquals(1, claimed.items().size());
            assertEquals(mutationId, claimed.items().getFirst().mutationId());
            assertEquals(2, claimed.items().getFirst().attemptCount());
            assertAudit(runtime, mutationId, "mutation_review_retried");
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void cancellationIsTerminalAndCannotBeResolvedTwice() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("cancel.db"));
        try {
            SQLitePendingMutationRepository repository =
                    new SQLitePendingMutationRepository(runtime);
            SQLitePendingMutationReviewStore reviewStore =
                    new SQLitePendingMutationReviewStore(runtime);
            UUID mutationId = seedReviewRequired(repository, 1_000L);
            Instant now = Instant.ofEpochMilli(5_000L);

            PendingMutationReviewStore.Status cancelled = reviewStore.resolve(
                            mutationId,
                            TEMPLATE_UPDATE,
                            PendingMutationReviewStore.Resolution.CANCEL,
                            audit(mutationId, "mutation_review_cancelled", now),
                            now)
                    .toCompletableFuture().join();
            PendingMutationReviewStore.Status repeated = reviewStore.resolve(
                            mutationId,
                            TEMPLATE_UPDATE,
                            PendingMutationReviewStore.Resolution.RETRY,
                            audit(
                                    mutationId,
                                    "mutation_review_retried",
                                    Instant.ofEpochMilli(6_000L)),
                            Instant.ofEpochMilli(6_000L))
                    .toCompletableFuture().join();
            Page<PendingMutationRecord> claim = repository.claimPending(
                            TEMPLATE_UPDATE,
                            "worker",
                            Instant.ofEpochMilli(7_000L),
                            Duration.ofSeconds(30),
                            10)
                    .toCompletableFuture().join();

            assertEquals(PendingMutationReviewStore.Status.CANCELLED, cancelled);
            assertEquals(PendingMutationReviewStore.Status.NOT_REVIEW_REQUIRED, repeated);
            assertTrue(claim.items().isEmpty());
            assertTrue(repository.listNonTerminal(TEMPLATE_UPDATE, PageRequest.first(10))
                    .toCompletableFuture().join().items().isEmpty());
            assertAudit(runtime, mutationId, "mutation_review_cancelled");
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void typeMismatchDoesNotMutateOrAppendAudit() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("type-mismatch.db"));
        try {
            SQLitePendingMutationRepository repository =
                    new SQLitePendingMutationRepository(runtime);
            SQLitePendingMutationReviewStore reviewStore =
                    new SQLitePendingMutationReviewStore(runtime);
            UUID mutationId = seedReviewRequired(repository, 1_000L);
            Instant now = Instant.ofEpochMilli(5_000L);

            PendingMutationReviewStore.Status mismatch = reviewStore.resolve(
                            mutationId,
                            "INSTANCE_REMOVAL",
                            PendingMutationReviewStore.Resolution.CANCEL,
                            audit(mutationId, "mutation_review_cancelled", now),
                            now)
                    .toCompletableFuture().join();

            assertEquals(PendingMutationReviewStore.Status.TYPE_MISMATCH, mismatch);
            assertEquals(PendingMutationState.REVIEW_REQUIRED, repository
                    .listNonTerminal(TEMPLATE_UPDATE, PageRequest.first(10))
                    .toCompletableFuture().join().items().getFirst().state());
            assertTrue(audits(runtime, mutationId).items().isEmpty());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static UUID seedReviewRequired(
            SQLitePendingMutationRepository repository, long createdAt) {
        UUID mutationId = UUID.randomUUID();
        repository.insert(new PendingMutationRecord(
                        mutationId,
                        TEMPLATE_UPDATE,
                        null,
                        null,
                        null,
                        PendingMutationState.PENDING,
                        null,
                        null,
                        0,
                        null,
                        createdAt,
                        createdAt))
                .toCompletableFuture().join();
        PendingMutationRecord claimed = repository.claimPending(
                        TEMPLATE_UPDATE,
                        "initial-worker",
                        Instant.ofEpochMilli(2_000L),
                        Duration.ofSeconds(30),
                        1)
                .toCompletableFuture().join().items().getFirst();
        assertTrue(repository.transitionClaimed(
                        mutationId,
                        PendingMutationState.CLAIMED,
                        PendingMutationState.REVIEW_REQUIRED,
                        claimed.claimToken(),
                        Instant.ofEpochMilli(3_000L))
                .toCompletableFuture().join());
        return mutationId;
    }

    private static AuditEventRecord audit(UUID mutationId, String eventType, Instant now) {
        return AuditEventRecord.pending(
                "pending_mutation",
                mutationId.toString(),
                eventType,
                "STAFF",
                "test-operator",
                "{\"reason\":\"verified by test\"}",
                now.toEpochMilli());
    }

    private static void assertAudit(
            SQLiteStorageRuntime runtime, UUID mutationId, String eventType) {
        Page<AuditEventRecord> audit = audits(runtime, mutationId);
        assertEquals(1, audit.items().size());
        assertEquals(eventType, audit.items().getFirst().eventType());
    }

    private static Page<AuditEventRecord> audits(
            SQLiteStorageRuntime runtime, UUID mutationId) {
        return new SQLiteAuditRepository(runtime)
                .listByAggregate("pending_mutation", mutationId.toString(), PageRequest.first(10))
                .toCompletableFuture().join();
    }

    private static SQLiteStorageRuntime start(Path database) {
        MetricsPort metrics = MetricsPort.noOp();
        SQLiteStorageRuntime runtime = new SQLiteStorageRuntime(
                new SQLiteConnectionFactory(database, 5_000),
                new MigrationRunner(),
                new BoundedDatabaseExecutor("test-database", 32, metrics),
                metrics);
        assertEquals(
                net.enthusia.loreitems.application.StorageState.READ_WRITE,
                runtime.start().toCompletableFuture().join().state());
        return runtime;
    }
}
