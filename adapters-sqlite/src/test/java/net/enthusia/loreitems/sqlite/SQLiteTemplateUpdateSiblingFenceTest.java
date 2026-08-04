package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.PendingMutationRecord;
import net.enthusia.loreitems.application.TemplateUpdatePrepareResult;
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

class SQLiteTemplateUpdateSiblingFenceTest {
    private static final TemplateRevision REVISION_ONE = new TemplateRevision(1L);
    private static final TemplateRevision REVISION_TWO = new TemplateRevision(2L);

    @TempDir
    Path temporaryDirectory;

    @Test
    void everySiblingRevisionEntersReviewInsteadOfAllowingTheLastOneToRun() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("siblings.db"));
        try {
            Scenario scenario = seedRollout(runtime);
            SQLitePendingMutationRepository repository =
                    new SQLitePendingMutationRepository(runtime);
            repository.insert(new PendingMutationRecord(
                            UUID.randomUUID(),
                            "TEMPLATE_UPDATE",
                            scenario.definitionId(),
                            scenario.instanceId(),
                            REVISION_ONE.value(),
                            PendingMutationState.PENDING,
                            null,
                            null,
                            0,
                            null,
                            1_500L,
                            1_500L))
                    .toCompletableFuture().join();

            TemplateUpdatePrepareResult first = repository.prepareTemplateUpdate(
                            scenario.identity(),
                            "worker-a",
                            Instant.ofEpochMilli(5_000L),
                            Duration.ofSeconds(30L))
                    .toCompletableFuture().join();
            TemplateUpdatePrepareResult second = repository.prepareTemplateUpdate(
                            scenario.identity(),
                            "worker-b",
                            Instant.ofEpochMilli(5_100L),
                            Duration.ofSeconds(30L))
                    .toCompletableFuture().join();
            TemplateUpdatePrepareResult third = repository.prepareTemplateUpdate(
                            scenario.identity(),
                            "worker-c",
                            Instant.ofEpochMilli(5_200L),
                            Duration.ofSeconds(30L))
                    .toCompletableFuture().join();

            assertEquals(TemplateUpdatePrepareResult.Status.REVIEW_REQUIRED, first.status());
            assertEquals(TemplateUpdatePrepareResult.Status.REVIEW_REQUIRED, second.status());
            assertEquals(TemplateUpdatePrepareResult.Status.NO_PENDING_WORK, third.status());
            Page<PendingMutationRecord> mutations = repository.listNonTerminal(
                            "TEMPLATE_UPDATE", PageRequest.first(10))
                    .toCompletableFuture().join();
            assertEquals(2, mutations.items().size());
            assertTrue(mutations.items().stream().allMatch(mutation ->
                    mutation.state() == PendingMutationState.REVIEW_REQUIRED
                            && mutation.attemptCount() == 0));
        } finally {
            runtime.close(Duration.ofSeconds(5L));
        }
    }

    private static Scenario seedRollout(SQLiteStorageRuntime runtime) {
        LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
        LoreInstanceId instanceId = new LoreInstanceId(UUID.randomUUID());
        new SQLiteDefinitionRepository(runtime).create(
                        new LoreDefinition(
                                definitionId,
                                new DefinitionKey("siblings-" + definitionId.value()),
                                "Sibling Test",
                                REVISION_ONE,
                                1_000L,
                                null),
                        new LoreDefinitionRevision(
                                definitionId,
                                REVISION_ONE,
                                1,
                                new byte[] {1},
                                1_000L))
                .toCompletableFuture().join();
        new SQLiteInstanceRepository(runtime).create(new LoreInstance(
                        instanceId,
                        definitionId,
                        REVISION_ONE,
                        REVISION_ONE,
                        LoreInstanceLifecycle.ACTIVE,
                        1_100L,
                        null))
                .toCompletableFuture().join();
        new SQLiteTemplateRevisionRolloutStore(runtime).start(
                        new LoreDefinitionRevision(
                                definitionId,
                                REVISION_TWO,
                                1,
                                new byte[] {2},
                                2_000L),
                        REVISION_ONE,
                        AuditEventRecord.pending(
                                "lore_definition",
                                definitionId.value().toString(),
                                "template_revision_started",
                                "player",
                                UUID.randomUUID().toString(),
                                "{\"previousRevision\":1,\"targetRevision\":2}",
                                2_000L),
                        1)
                .toCompletableFuture().join();
        return new Scenario(definitionId, instanceId);
    }

    private static SQLiteStorageRuntime start(Path database) {
        MetricsPort metrics = MetricsPort.noOp();
        SQLiteStorageRuntime runtime = new SQLiteStorageRuntime(
                new SQLiteConnectionFactory(database, 5_000),
                new MigrationRunner(),
                new BoundedDatabaseExecutor("test-database", 64, metrics),
                metrics);
        assertEquals(
                net.enthusia.loreitems.application.StorageState.READ_WRITE,
                runtime.start().toCompletableFuture().join().state());
        return runtime;
    }

    private record Scenario(
            LoreDefinitionId definitionId,
            LoreInstanceId instanceId) {
        private LoreItemIdentity identity() {
            return new LoreItemIdentity(definitionId, instanceId, REVISION_ONE);
        }
    }
}
