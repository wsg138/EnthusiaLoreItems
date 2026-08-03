package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.PendingMutationRecord;
import net.enthusia.loreitems.domain.PendingMutationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLitePendingMutationRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void claimsOnlyDueMutationsAndFencesTransitions() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("claims.db"));
        try {
            SQLitePendingMutationRepository repository =
                    new SQLitePendingMutationRepository(runtime);
            UUID dueId = UUID.randomUUID();
            repository.insert(pending(dueId, null, 1_000L)).toCompletableFuture().join();
            repository.insert(pending(UUID.randomUUID(), 9_000L, 1_001L))
                    .toCompletableFuture()
                    .join();

            Page<PendingMutationRecord> claimed = repository
                    .claimPending(
                            "worker-a",
                            Instant.ofEpochMilli(2_000L),
                            Duration.ofSeconds(30),
                            10)
                    .toCompletableFuture()
                    .join();
            Page<PendingMutationRecord> duplicateClaim = repository
                    .claimPending(
                            "worker-b",
                            Instant.ofEpochMilli(2_001L),
                            Duration.ofSeconds(30),
                            10)
                    .toCompletableFuture()
                    .join();

            assertEquals(1, claimed.items().size());
            assertEquals(dueId, claimed.items().getFirst().mutationId());
            assertTrue(duplicateClaim.items().isEmpty());
            assertFalse(repository.transitionClaimed(
                            dueId,
                            PendingMutationState.CLAIMED,
                            PendingMutationState.APPLIED,
                            "worker-b",
                            Instant.ofEpochMilli(3_000L))
                    .toCompletableFuture()
                    .join());
            assertFalse(repository.transitionClaimed(
                            dueId,
                            PendingMutationState.CLAIMED,
                            PendingMutationState.APPLIED,
                            "worker-a",
                            Instant.ofEpochMilli(32_001L))
                    .toCompletableFuture()
                    .join());
            assertTrue(repository.transitionClaimed(
                            dueId,
                            PendingMutationState.CLAIMED,
                            PendingMutationState.APPLIED,
                            "worker-a",
                            Instant.ofEpochMilli(3_000L))
                    .toCompletableFuture()
                    .join());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void expiredClaimBecomesReviewRequiredAfterRestart() {
        Path database = temporaryDirectory.resolve("restart.db");
        UUID mutationId = UUID.randomUUID();
        UUID secondMutationId = UUID.randomUUID();
        SQLiteStorageRuntime firstRuntime = start(database);
        SQLitePendingMutationRepository firstRepository =
                new SQLitePendingMutationRepository(firstRuntime);
        firstRepository.insert(pending(mutationId, null, 1_000L)).toCompletableFuture().join();
        firstRepository
                .insert(pending(secondMutationId, null, 1_001L))
                .toCompletableFuture()
                .join();
        firstRepository.claimPending(
                        "worker-before-restart",
                        Instant.ofEpochMilli(2_000L),
                        Duration.ofMillis(10L),
                        10)
                .toCompletableFuture()
                .join();
        firstRuntime.close(Duration.ofSeconds(5));

        SQLiteStorageRuntime secondRuntime = start(database);
        try {
            SQLitePendingMutationRepository secondRepository =
                    new SQLitePendingMutationRepository(secondRuntime);
            int recovered = secondRepository
                    .moveExpiredClaimsToReview(Instant.ofEpochMilli(2_011L), 1)
                    .toCompletableFuture()
                    .join();
            Page<PendingMutationRecord> remaining = secondRepository
                    .listNonTerminal(PageRequest.first(10))
                    .toCompletableFuture()
                    .join();

            assertEquals(1, recovered);
            assertEquals(2, remaining.items().size());
            assertEquals(
                    1L,
                    remaining.items().stream()
                            .filter(record -> record.state() == PendingMutationState.REVIEW_REQUIRED)
                            .count());
            assertEquals(
                    1L,
                    remaining.items().stream()
                            .filter(record -> record.state() == PendingMutationState.CLAIMED)
                            .count());
            assertEquals(
                    1,
                    secondRepository
                            .moveExpiredClaimsToReview(Instant.ofEpochMilli(2_011L), 1)
                            .toCompletableFuture()
                            .join());
        } finally {
            secondRuntime.close(Duration.ofSeconds(5));
        }
    }

    private static PendingMutationRecord pending(
            UUID mutationId, Long nextAttemptAt, long createdAt) {
        return new PendingMutationRecord(
                mutationId,
                "TEMPLATE_UPDATE",
                null,
                null,
                null,
                PendingMutationState.PENDING,
                null,
                null,
                0,
                nextAttemptAt,
                createdAt,
                createdAt);
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
