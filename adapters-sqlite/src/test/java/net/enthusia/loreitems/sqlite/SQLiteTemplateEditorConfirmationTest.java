package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.EncodedItemTemplate;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.TemplateManagementSnapshot;
import net.enthusia.loreitems.application.TemplateRevisionConfirmation;
import net.enthusia.loreitems.application.TemplateRevisionStartResult;
import net.enthusia.loreitems.application.TemplateRevisionStartStatus;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreDefinitionRevision;
import net.enthusia.loreitems.domain.LoreInstance;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.LoreInstanceLifecycle;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    class SQLiteTemplateEditorConfirmationTest {
    private static final TemplateRevision REVISION_ONE = new TemplateRevision(1);
    private static final TemplateRevision REVISION_TWO = new TemplateRevision(2);
    private static final long CONFIRMED_AT = 2_000L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void replayAfterRestartReturnsStoredResultWithoutDuplicateIntent() {
        Path database = temporaryDirectory.resolve("replay.db");
        Scenario scenario = seed(database, 1);
        UUID confirmationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        TemplateRevisionConfirmation confirmation = confirmation(
                confirmationId, scenario.definitionId(), actorId, new byte[] {2}, 10);

        SQLiteStorageRuntime firstRuntime = start(database);
        try {
            TemplateRevisionStartResult first = new SQLiteTemplateRevisionRolloutStore(firstRuntime)
                    .startConfirmed(confirmation).toCompletableFuture().join();
            assertEquals(TemplateRevisionStartStatus.STARTED, first.status());
        } finally {
            firstRuntime.close(Duration.ofSeconds(5));
        }

        SQLiteStorageRuntime restarted = start(database);
        try {
            TemplateRevisionStartResult replay = new SQLiteTemplateRevisionRolloutStore(restarted)
                    .startConfirmed(confirmation).toCompletableFuture().join();

            assertEquals(TemplateRevisionStartStatus.ALREADY_STARTED, replay.status());
            assertEquals(REVISION_TWO, replay.currentRevision());
            assertEquals(1, confirmationCount(restarted));
            assertEquals(1, mutationCount(restarted));
            assertEquals(1, auditCount(restarted, scenario.definitionId()));
        } finally {
            restarted.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void reusedConfirmationWithDifferentTemplateIsRejectedAsConflict() {
        Path database = temporaryDirectory.resolve("mismatch.db");
        Scenario scenario = seed(database, 0);
        UUID confirmationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        SQLiteStorageRuntime runtime = start(database);
        try {
            SQLiteTemplateRevisionRolloutStore store =
                    new SQLiteTemplateRevisionRolloutStore(runtime);
            TemplateRevisionStartResult first = store.startConfirmed(confirmation(
                            confirmationId,
                            scenario.definitionId(),
                            actorId,
                            new byte[] {2},
                            10))
                    .toCompletableFuture().join();
            TemplateRevisionStartResult mismatch = store.startConfirmed(confirmation(
                            confirmationId,
                            scenario.definitionId(),
                            actorId,
                            new byte[] {9},
                            10))
                    .toCompletableFuture().join();

            assertEquals(TemplateRevisionStartStatus.STARTED, first.status());
            assertEquals(TemplateRevisionStartStatus.REVISION_CONFLICT, mismatch.status());
            assertEquals(REVISION_TWO, mismatch.currentRevision());
            assertEquals(1, confirmationCount(runtime));
            assertEquals(1, auditCount(runtime, scenario.definitionId()));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void beforeEvidenceMustMatchThePersistedExpectedRevision() {
        Path database = temporaryDirectory.resolve("before-mismatch.db");
        Scenario scenario = seed(database, 0);
        SQLiteStorageRuntime runtime = start(database);
        try {
            TemplateRevisionStartResult result = new SQLiteTemplateRevisionRolloutStore(runtime)
                    .startConfirmed(confirmation(
                            UUID.randomUUID(),
                            scenario.definitionId(),
                            UUID.randomUUID(),
                            new byte[] {9},
                            new byte[] {2},
                            10))
                    .toCompletableFuture().join();

            assertEquals(TemplateRevisionStartStatus.REVISION_CONFLICT, result.status());
            assertEquals(REVISION_ONE, result.currentRevision());
            assertEquals(0, confirmationCount(runtime));
            assertEquals(0, mutationCount(runtime));
            assertEquals(0, auditCount(runtime, scenario.definitionId()));
            assertTrue(new SQLiteDefinitionRepository(runtime)
                    .findRevision(scenario.definitionId(), REVISION_TWO)
                    .toCompletableFuture().join().isEmpty());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void replayWithAlteredBeforeEvidenceIsRejected() {
        Path database = temporaryDirectory.resolve("replay-before-mismatch.db");
        Scenario scenario = seed(database, 0);
        UUID confirmationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        SQLiteStorageRuntime runtime = start(database);
        try {
            SQLiteTemplateRevisionRolloutStore store =
                    new SQLiteTemplateRevisionRolloutStore(runtime);
            TemplateRevisionStartResult first = store.startConfirmed(confirmation(
                            confirmationId,
                            scenario.definitionId(),
                            actorId,
                            new byte[] {1},
                            new byte[] {2},
                            10))
                    .toCompletableFuture().join();
            TemplateRevisionStartResult mismatch = store.startConfirmed(confirmation(
                            confirmationId,
                            scenario.definitionId(),
                            actorId,
                            new byte[] {9},
                            new byte[] {2},
                            10))
                    .toCompletableFuture().join();

            assertEquals(TemplateRevisionStartStatus.STARTED, first.status());
            assertEquals(TemplateRevisionStartStatus.REVISION_CONFLICT, mismatch.status());
            assertEquals(1, confirmationCount(runtime));
            assertEquals(1, auditCount(runtime, scenario.definitionId()));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void failedInitialBatchRollsBackConfirmationRevisionAuditAndDesiredRevision() {
        Path database = temporaryDirectory.resolve("rollback-confirmation.db");
        Scenario scenario = seed(database, 2);
        UUID duplicateMutationId = UUID.randomUUID();
        SQLiteStorageRuntime runtime = start(database);
        try {
            SQLiteTemplateRevisionRolloutStore store = new SQLiteTemplateRevisionRolloutStore(
                    runtime, () -> duplicateMutationId);

            assertThrows(
                    CompletionException.class,
                    () -> store.startConfirmed(confirmation(
                                    UUID.randomUUID(),
                                    scenario.definitionId(),
                                    UUID.randomUUID(),
                                    new byte[] {2},
                                    2))
                            .toCompletableFuture().join());

            LoreDefinition definition = new SQLiteDefinitionRepository(runtime)
                    .findById(scenario.definitionId()).toCompletableFuture().join().orElseThrow();
            assertEquals(REVISION_ONE, definition.currentRevision());
            assertTrue(new SQLiteDefinitionRepository(runtime)
                    .findRevision(scenario.definitionId(), REVISION_TWO)
                    .toCompletableFuture().join().isEmpty());
            assertEquals(0, confirmationCount(runtime));
            assertEquals(0, mutationCount(runtime));
            assertEquals(0, auditCount(runtime, scenario.definitionId()));
            assertTrue(new SQLiteInstanceRepository(runtime)
                    .listByDefinition(scenario.definitionId(), PageRequest.first(10))
                    .toCompletableFuture().join().items().stream()
                    .allMatch(instance -> instance.desiredRevision().equals(REVISION_ONE)));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void managementSnapshotReturnsBoundedCountsAndCurrentTemplate() {
        Path database = temporaryDirectory.resolve("snapshot.db");
        Scenario scenario = seed(database, 2);
        SQLiteStorageRuntime runtime = start(database);
        try {
            createAnomaly(runtime, scenario);
            new SQLiteTemplateRevisionRolloutStore(runtime)
                    .startConfirmed(confirmation(
                            UUID.randomUUID(),
                            scenario.definitionId(),
                            UUID.randomUUID(),
                            new byte[] {7, 8, 9},
                            1))
                    .toCompletableFuture().join();

            TemplateManagementSnapshot snapshot = new SQLiteTemplateManagementQueryStore(runtime)
                    .findSnapshot(scenario.definitionId())
                    .toCompletableFuture().join().orElseThrow();

            assertEquals(REVISION_TWO, snapshot.definition().currentRevision());
            assertEquals(2L, snapshot.activeInstanceCount());
            assertEquals(1L, snapshot.anomalyCount());
            assertEquals(2L, snapshot.pendingUpdateCount());
            assertEquals(1, snapshot.currentTemplate().codecVersion());
            assertEquals(List.of((byte) 7, (byte) 8, (byte) 9), bytes(snapshot));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static List<Byte> bytes(TemplateManagementSnapshot snapshot) {
        byte[] payload = snapshot.currentTemplate().payload();
        return List.of(payload[0], payload[1], payload[2]);
    }

    private static void createAnomaly(SQLiteStorageRuntime runtime, Scenario scenario) {
        new SQLiteAnomalyRepository(runtime).create(new InstanceAnomaly(
                        UUID.randomUUID(),
                        scenario.instanceIds().getFirst(),
                        scenario.definitionId(),
                        InstanceAnomaly.Type.MALFORMED_STACK,
                        InstanceAnomaly.Status.OPEN,
                        "Malformed stack remains preserved for review.",
                        1_000L,
                        1_000L,
                        null,
                        null,
                        null,
                        null,
                        0L))
                .toCompletableFuture().join();
    }

    private static TemplateRevisionConfirmation confirmation(
            UUID confirmationId,
            LoreDefinitionId definitionId,
            UUID actorId,
            byte[] template,
            int batchLimit) {
        return confirmation(
                confirmationId,
                definitionId,
                actorId,
                new byte[] {1},
                template,
                batchLimit);
    }

    private static TemplateRevisionConfirmation confirmation(
            UUID confirmationId,
            LoreDefinitionId definitionId,
            UUID actorId,
            byte[] beforeTemplate,
            byte[] template,
            int batchLimit) {
        LoreDefinitionRevision revision = new LoreDefinitionRevision(
                definitionId,
                REVISION_TWO,
                1,
                template,
                CONFIRMED_AT);
        AuditEventRecord audit = AuditEventRecord.pending(
                "lore_definition",
                definitionId.value().toString(),
                "template_revision_started",
                "player",
                actorId.toString(),
                "{\"previousRevision\":1,\"targetRevision\":2}",
                CONFIRMED_AT);
        return new TemplateRevisionConfirmation(
                confirmationId,
                revision,
                REVISION_ONE,
                new EncodedItemTemplate(1, beforeTemplate),
                audit,
                actorId,
                batchLimit);
    }

    private static Scenario seed(Path database, int instanceCount) {
        SQLiteStorageRuntime runtime = start(database);
        try {
            LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
            new SQLiteDefinitionRepository(runtime).create(
                            new LoreDefinition(
                                    definitionId,
                                    new DefinitionKey("editor-test"),
                                    "Editor Test",
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
            SQLiteInstanceRepository repository = new SQLiteInstanceRepository(runtime);
            java.util.ArrayList<LoreInstanceId> instanceIds = new java.util.ArrayList<>();
            for (int index = 0; index < instanceCount; index++) {
                LoreInstanceId instanceId = new LoreInstanceId(UUID.randomUUID());
                repository.create(new LoreInstance(
                                instanceId,
                                definitionId,
                                REVISION_ONE,
                                REVISION_ONE,
                                LoreInstanceLifecycle.ACTIVE,
                                10L + index,
                                null))
                        .toCompletableFuture().join();
                instanceIds.add(instanceId);
            }
            return new Scenario(definitionId, List.copyOf(instanceIds));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static int confirmationCount(SQLiteStorageRuntime runtime) {
        return runtime.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                         "SELECT COUNT(*) FROM template_edit_confirmations");
                 ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }).toCompletableFuture().join();
    }

    private static int mutationCount(SQLiteStorageRuntime runtime) {
        return new SQLitePendingMutationRepository(runtime)
                .listNonTerminal(PageRequest.first(20))
                .toCompletableFuture().join().items().size();
    }

    private static int auditCount(SQLiteStorageRuntime runtime, LoreDefinitionId definitionId) {
        return new SQLiteAuditRepository(runtime)
                .listByAggregate(
                        "lore_definition",
                        definitionId.value().toString(),
                        PageRequest.first(20))
                .toCompletableFuture().join().items().size();
    }

    private static SQLiteStorageRuntime start(Path database) {
        MetricsPort metrics = MetricsPort.noOp();
        SQLiteStorageRuntime runtime = new SQLiteStorageRuntime(
                new SQLiteConnectionFactory(database, 5_000),
                new MigrationRunner(),
                new BoundedDatabaseExecutor("template-editor-test", 32, metrics),
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
