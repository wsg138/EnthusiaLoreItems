package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.PendingMutationRecord;
import net.enthusia.loreitems.application.TemplateRevisionRolloutBatchResult;
import net.enthusia.loreitems.application.TemplateRevisionRolloutBatchStatus;
import net.enthusia.loreitems.application.TemplateRevisionRolloutCandidate;
import net.enthusia.loreitems.application.TemplateRevisionStartResult;
import net.enthusia.loreitems.application.TemplateRevisionStartStatus;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreDefinitionRevision;
import net.enthusia.loreitems.domain.LoreInstance;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.LoreInstanceLifecycle;
import net.enthusia.loreitems.domain.PendingMutationState;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteTemplateRevisionRolloutStoreTest {
    private static final TemplateRevision REVISION_ONE = new TemplateRevision(1);
    private static final TemplateRevision REVISION_TWO = new TemplateRevision(2);

    @TempDir
    Path temporaryDirectory;

    @Test
    void schedulesBoundedBatchesAndResumesPlanningAfterRestart() {
        Path database = temporaryDirectory.resolve("rollout.db");
        Scenario scenario = seed(database, 3);
        startFirstBatch(database, scenario);
        finishAfterRestart(database, scenario);
    }

    @Test
    void rollsBackRevisionInstancesMutationsAndAuditWhenBatchInsertionFails() {
        Path database = temporaryDirectory.resolve("rollback.db");
        Scenario scenario = seed(database, 2);
        SQLiteStorageRuntime runtime = start(database);
        try {
            UUID duplicateMutationId = UUID.randomUUID();
            SQLiteTemplateRevisionRolloutStore store = new SQLiteTemplateRevisionRolloutStore(
                    runtime, () -> duplicateMutationId);

            assertThrows(
                    CompletionException.class,
                    () -> store.start(
                                    revisionTwo(scenario.definitionId()),
                                    REVISION_ONE,
                                    audit(scenario.definitionId()),
                                    2)
                            .toCompletableFuture().join());

            SQLiteDefinitionRepository definitions = new SQLiteDefinitionRepository(runtime);
            assertEquals(
                    REVISION_ONE,
                    definitions.findById(scenario.definitionId())
                            .toCompletableFuture().join().orElseThrow().currentRevision());
            assertTrue(definitions.findRevision(scenario.definitionId(), REVISION_TWO)
                    .toCompletableFuture().join().isEmpty());
            assertEquals(0, mutations(runtime).items().size());
            assertEquals(0, definitionAudit(runtime, scenario.definitionId()).items().size());
            assertTrue(instances(runtime, scenario.definitionId()).items().stream()
                    .allMatch(instance -> instance.desiredRevision().equals(REVISION_ONE)));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void rejectsDuplicateTemplateUpdateIntentForTheSameInstanceRevision() {
        Path database = temporaryDirectory.resolve("idempotency.db");
        Scenario scenario = seed(database, 1);
        SQLiteStorageRuntime runtime = start(database);
        try {
            SQLitePendingMutationRepository repository =
                    new SQLitePendingMutationRepository(runtime);
            PendingMutationRecord first = pendingTemplateUpdate(
                    UUID.randomUUID(), scenario, 1_000L);
            PendingMutationRecord duplicate = pendingTemplateUpdate(
                    UUID.randomUUID(), scenario, 1_001L);

            repository.insert(first).toCompletableFuture().join();
            assertThrows(
                    CompletionException.class,
                    () -> repository.insert(duplicate).toCompletableFuture().join());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void blocksAnotherRevisionUntilTheCurrentPhysicalWorkIsTerminal() {
        Path database = temporaryDirectory.resolve("serial.db");
        Scenario scenario = seed(database, 1);
        SQLiteStorageRuntime runtime = start(database);
        try {
            SQLiteTemplateRevisionRolloutStore store = new SQLiteTemplateRevisionRolloutStore(
                    runtime, uniqueIds(2));
            TemplateRevisionStartResult first = store.start(
                            revisionTwo(scenario.definitionId()),
                            REVISION_ONE,
                            audit(scenario.definitionId()),
                            10)
                    .toCompletableFuture().join();
            TemplateRevisionStartResult second = store.start(
                            new LoreDefinitionRevision(
                                    scenario.definitionId(),
                                    new TemplateRevision(3),
                                    1,
                                    new byte[] {3},
                                    3_000L),
                            REVISION_TWO,
                            AuditEventRecord.pending(
                                    "lore_definition",
                                    scenario.definitionId().value().toString(),
                                    "template_revision_started",
                                    "player",
                                    UUID.randomUUID().toString(),
                                    "{\"previousRevision\":2,\"targetRevision\":3}",
                                    3_000L),
                            10)
                    .toCompletableFuture().join();

            assertEquals(TemplateRevisionStartStatus.STARTED, first.status());
            assertEquals(TemplateRevisionStartStatus.ROLLOUT_IN_PROGRESS, second.status());
            assertEquals(REVISION_TWO, second.currentRevision());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static void startFirstBatch(Path database, Scenario scenario) {
        SQLiteStorageRuntime runtime = start(database);
        try {
            SQLiteTemplateRevisionRolloutStore store = new SQLiteTemplateRevisionRolloutStore(
                    runtime, uniqueIds(3));
            TemplateRevisionStartResult result = store.start(
                            revisionTwo(scenario.definitionId()),
                            REVISION_ONE,
                            audit(scenario.definitionId()),
                            2)
                    .toCompletableFuture().join();

            assertEquals(TemplateRevisionStartStatus.STARTED, result.status());
            assertEquals(REVISION_TWO, result.currentRevision());
            assertEquals(
                    TemplateRevisionRolloutBatchStatus.SCHEDULED,
                    result.initialBatch().status());
            assertEquals(2, result.initialBatch().scheduledCount());
            assertTrue(result.initialBatch().hasMore());
            assertEquals(2, mutations(runtime).items().size());
            assertEquals(1, definitionAudit(runtime, scenario.definitionId()).items().size());
            assertEquals(2L, instances(runtime, scenario.definitionId()).items().stream()
                    .filter(instance -> instance.desiredRevision().equals(REVISION_TWO))
                    .count());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static void finishAfterRestart(Path database, Scenario scenario) {
        SQLiteStorageRuntime runtime = start(database);
        try {
            SQLiteTemplateRevisionRolloutStore store = new SQLiteTemplateRevisionRolloutStore(
                    runtime, uniqueIds(2));
            Page<TemplateRevisionRolloutCandidate> incomplete = store
                    .listIncomplete(PageRequest.first(10)).toCompletableFuture().join();
            assertEquals(1, incomplete.items().size());
            TemplateRevisionRolloutBatchResult result = store.scheduleNextBatch(
                            incomplete.items().getFirst(), 2_000L, 2)
                    .toCompletableFuture().join();
            TemplateRevisionRolloutBatchResult retry = store.scheduleNextBatch(
                            incomplete.items().getFirst(), 2_001L, 2)
                    .toCompletableFuture().join();

            assertEquals(TemplateRevisionRolloutBatchStatus.COMPLETE, result.status());
            assertEquals(1, result.scheduledCount());
            assertFalse(result.hasMore());
            assertEquals(TemplateRevisionRolloutBatchStatus.COMPLETE, retry.status());
            assertEquals(0, retry.scheduledCount());
            assertEquals(3, mutations(runtime).items().size());
            assertTrue(store.listIncomplete(PageRequest.first(10))
                    .toCompletableFuture().join().items().isEmpty());
            assertTrue(instances(runtime, scenario.definitionId()).items().stream()
                    .allMatch(instance -> instance.desiredRevision().equals(REVISION_TWO)));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static Scenario seed(Path database, int instanceCount) {
        SQLiteStorageRuntime runtime = start(database);
        try {
            LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
            new SQLiteDefinitionRepository(runtime).create(
                            new LoreDefinition(
                                    definitionId,
                                    new DefinitionKey("rollout-test"),
                                    "Rollout Test",
                                    REVISION_ONE,
                                    1L,
                                    null),
                            new LoreDefinitionRevision(
                                    definitionId,
                                    REVISION_ONE,
                                    1,
                                    new byte[] {1},
                                    1L))
                    .toCompletableFuture().join();
            SQLiteInstanceRepository instances = new SQLiteInstanceRepository(runtime);
            List<LoreInstanceId> instanceIds = new ArrayList<>();
            for (int index = 0; index < instanceCount; index++) {
                LoreInstanceId instanceId = new LoreInstanceId(UUID.randomUUID());
                instanceIds.add(instanceId);
                instances.create(new LoreInstance(
                                instanceId,
                                definitionId,
                                REVISION_ONE,
                                REVISION_ONE,
                                LoreInstanceLifecycle.ACTIVE,
                                10L + index,
                                null))
                        .toCompletableFuture().join();
            }
            return new Scenario(definitionId, List.copyOf(instanceIds));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static LoreDefinitionRevision revisionTwo(LoreDefinitionId definitionId) {
        return new LoreDefinitionRevision(
                definitionId, REVISION_TWO, 1, new byte[] {2}, 2_000L);
    }

    private static AuditEventRecord audit(LoreDefinitionId definitionId) {
        return AuditEventRecord.pending(
                "lore_definition",
                definitionId.value().toString(),
                "template_revision_started",
                "player",
                UUID.randomUUID().toString(),
                "{\"previousRevision\":1,\"targetRevision\":2}",
                2_000L);
    }

    private static Page<PendingMutationRecord> mutations(SQLiteStorageRuntime runtime) {
        return new SQLitePendingMutationRepository(runtime)
                .listNonTerminal(PageRequest.first(20)).toCompletableFuture().join();
    }

    private static Page<AuditEventRecord> definitionAudit(
            SQLiteStorageRuntime runtime, LoreDefinitionId definitionId) {
        return new SQLiteAuditRepository(runtime).listByAggregate(
                        "lore_definition",
                        definitionId.value().toString(),
                        PageRequest.first(20))
                .toCompletableFuture().join();
    }

    private static Page<LoreInstance> instances(
            SQLiteStorageRuntime runtime, LoreDefinitionId definitionId) {
        return new SQLiteInstanceRepository(runtime)
                .listByDefinition(definitionId, PageRequest.first(20))
                .toCompletableFuture().join();
    }

    private static PendingMutationRecord pendingTemplateUpdate(
            UUID mutationId, Scenario scenario, long createdAt) {
        return new PendingMutationRecord(
                mutationId,
                SQLiteTemplateRevisionRolloutStore.MUTATION_TYPE,
                scenario.definitionId(),
                scenario.instanceIds().getFirst(),
                REVISION_ONE.value(),
                PendingMutationState.PENDING,
                null,
                null,
                0,
                null,
                createdAt,
                createdAt);
    }

    private static Supplier<UUID> uniqueIds(int count) {
        Queue<UUID> ids = new ArrayDeque<>();
        for (int index = 0; index < count; index++) {
            ids.add(UUID.randomUUID());
        }
        return () -> {
            UUID next = ids.poll();
            if (next == null) {
                throw new IllegalStateException("No test mutation ID remains");
            }
            return next;
        };
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

    private record Scenario(
            LoreDefinitionId definitionId,
            List<LoreInstanceId> instanceIds) {}
}
