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
    private static final String TEMPLATE_UPDATE = "TEMPLATE_UPDATE";
    private static final String INSTANCE_REMOVAL = "INSTANCE_REMOVAL";
    private static final long LARGE_REVISION = (long) Integer.MAX_VALUE + 10L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void claimsOnlyDueMutationsOfTheRequestedTypeAndFencesTransitions() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("claims.db"));
        try {
            SQLitePendingMutationRepository repository =
                    new SQLitePendingMutationRepository(runtime);
            UUID dueId = seedPendingMutations(repository);
            assertClaimSelection(repository, dueId);
            assertClaimFencing(repository, dueId);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void typedMonitoringReturnsOnlyRequestedNonTerminalMutations() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("monitoring.db"));
        try {
            SQLitePendingMutationRepository repository =
                    new SQLitePendingMutationRepository(runtime);
            repository.insert(pending(UUID.randomUUID(), TEMPLATE_UPDATE, null, 1_000L))
                    .toCompletableFuture().join();
            repository.insert(pending(UUID.randomUUID(), INSTANCE_REMOVAL, null, 1_001L))
                    .toCompletableFuture().join();

            Page<PendingMutationRecord> templateUpdates = repository
                    .listNonTerminal(TEMPLATE_UPDATE, PageRequest.first(10))
                    .toCompletableFuture().join();

            assertEquals(1, templateUpdates.items().size());
            assertEquals(TEMPLATE_UPDATE, templateUpdates.items().getFirst().mutationType());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void preservesDesiredRevisionsAboveTheIntegerRange() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("long-revision.db"));
        try {
            SQLitePendingMutationRepository repository =
                    new SQLitePendingMutationRepository(runtime);
            repository.insert(pendingWithDesiredRevision(UUID.randomUUID(), LARGE_REVISION, 1_000L))
                    .toCompletableFuture().join();

            PendingMutationRecord restored = repository.listNonTerminal(PageRequest.first(10))
                    .toCompletableFuture().join().items().getFirst();

            assertEquals(LARGE_REVISION, restored.desiredRevision());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void expiredClaimBecomesReviewRequiredAfterRestart() {
        Path database = temporaryDirectory.resolve("restart.db");
        seedExpiredClaims(database);
        assertBoundedRecoveryAfterRestart(database);
    }

    private static UUID seedPendingMutations(SQLitePendingMutationRepository repository) {
        UUID dueId = UUID.randomUUID();
        repository.insert(pending(dueId, TEMPLATE_UPDATE, null, 1_000L))
                .toCompletableFuture().join();
        repository.insert(pending(UUID.randomUUID(), TEMPLATE_UPDATE, 9_000L, 1_001L))
                .toCompletableFuture().join();
        repository.insert(pending(UUID.randomUUID(), INSTANCE_REMOVAL, null, 1_002L))
                .toCompletableFuture().join();
        return dueId;
    }

    private static void assertClaimSelection(
            SQLitePendingMutationRepository repository, UUID dueId) {
        Page<PendingMutationRecord> claimed = repository.claimPending(
                        TEMPLATE_UPDATE,
                        "worker-a",
                        Instant.ofEpochMilli(2_000L),
                        Duration.ofSeconds(30),
                        10)
                .toCompletableFuture().join();
        Page<PendingMutationRecord> duplicateClaim = repository.claimPending(
                        TEMPLATE_UPDATE,
                        "worker-b",
                        Instant.ofEpochMilli(2_001L),
                        Duration.ofSeconds(30),
                        10)
                .toCompletableFuture().join();
        Page<PendingMutationRecord> otherType = repository.claimPending(
                        INSTANCE_REMOVAL,
                        "removal-worker",
                        Instant.ofEpochMilli(2_001L),
                        Duration.ofSeconds(30),
                        10)
                .toCompletableFuture().join();
        assertEquals(1, claimed.items().size());
        assertEquals(dueId, claimed.items().getFirst().mutationId());
        assertTrue(duplicateClaim.items().isEmpty());
        assertEquals(1, otherType.items().size());
        assertEquals(INSTANCE_REMOVAL, otherType.items().getFirst().mutationType());
    }

    private static void assertClaimFencing(
            SQLitePendingMutationRepository repository, UUID dueId) {
        assertFalse(transition(repository, dueId, "worker-b", 3_000L));
        assertFalse(transition(repository, dueId, "worker-a", 32_001L));
        assertTrue(transition(repository, dueId, "worker-a", 3_000L));
    }

    private static boolean transition(
            SQLitePendingMutationRepository repository, UUID mutationId, String worker, long now) {
        return repository.transitionClaimed(
                        mutationId,
                        PendingMutationState.CLAIMED,
                        PendingMutationState.APPLIED,
                        worker,
                        Instant.ofEpochMilli(now))
                .toCompletableFuture().join();
    }

    private static void seedExpiredClaims(Path database) {
        SQLiteStorageRuntime runtime = start(database);
        SQLitePendingMutationRepository repository = new SQLitePendingMutationRepository(runtime);
        repository.insert(pending(UUID.randomUUID(), TEMPLATE_UPDATE, null, 1_000L))
                .toCompletableFuture().join();
        repository.insert(pending(UUID.randomUUID(), TEMPLATE_UPDATE, null, 1_001L))
                .toCompletableFuture().join();
        repository.claimPending(
                        TEMPLATE_UPDATE,
                        "worker-before-restart",
                        Instant.ofEpochMilli(2_000L),
                        Duration.ofMillis(10L),
                        10)
                .toCompletableFuture().join();
        runtime.close(Duration.ofSeconds(5));
    }

    private static void assertBoundedRecoveryAfterRestart(Path database) {
        SQLiteStorageRuntime runtime = start(database);
        try {
            SQLitePendingMutationRepository repository =
                    new SQLitePendingMutationRepository(runtime);
            int recovered = repository.moveExpiredClaimsToReview(
                            Instant.ofEpochMilli(2_011L), 1)
                    .toCompletableFuture().join();
            Page<PendingMutationRecord> remaining = repository
                    .listNonTerminal(PageRequest.first(10)).toCompletableFuture().join();
            assertEquals(1, recovered);
            assertEquals(2, remaining.items().size());
            assertEquals(1L, countState(remaining, PendingMutationState.REVIEW_REQUIRED));
            assertEquals(1L, countState(remaining, PendingMutationState.CLAIMED));
            assertEquals(1, repository.moveExpiredClaimsToReview(
                            Instant.ofEpochMilli(2_011L), 1)
                    .toCompletableFuture().join());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static long countState(
            Page<PendingMutationRecord> records, PendingMutationState state) {
        return records.items().stream().filter(record -> record.state() == state).count();
    }

    private static PendingMutationRecord pending(
            UUID mutationId,
            String mutationType,
            Long nextAttemptAt,
            long createdAt) {
        return new PendingMutationRecord(
                mutationId,
                mutationType,
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

    private static PendingMutationRecord pendingWithDesiredRevision(
            UUID mutationId, long desiredRevision, long createdAt) {
        return new PendingMutationRecord(
                mutationId,
                TEMPLATE_UPDATE,
                null,
                null,
                desiredRevision,
                PendingMutationState.PENDING,
                null,
                null,
                0,
                null,
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
