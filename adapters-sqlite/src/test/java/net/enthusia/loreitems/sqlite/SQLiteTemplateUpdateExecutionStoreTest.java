package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.PreparedTemplateUpdate;
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

class SQLiteTemplateUpdateExecutionStoreTest {
    private static final TemplateRevision REVISION_ONE = new TemplateRevision(1L);
    private static final TemplateRevision REVISION_TWO = new TemplateRevision(2L);
    private static final Instant CLAIM_TIME = Instant.ofEpochMilli(5_000L);
    private static final Duration LEASE = Duration.ofSeconds(30L);

    @TempDir
    Path temporaryDirectory;

    @Test
    void releasesAnUnappliedClaimThenCompletesTheRetriedPhysicalUpdate() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("complete.db"));
        try {
            Scenario scenario = seedRollout(runtime);
            SQLitePendingMutationRepository repository =
                    new SQLitePendingMutationRepository(runtime);
            PreparedTemplateUpdate first = prepare(repository, scenario, "worker-a");

            assertTrue(repository.releaseTemplateUpdate(
                            first,
                            "Item moved before the main-thread operation.",
                            Instant.ofEpochMilli(5_100L))
                    .toCompletableFuture().join());
            assertEquals(PendingMutationState.PENDING, mutationState(runtime, scenario));

            PreparedTemplateUpdate retried = prepare(repository, scenario, "worker-b");
            assertTrue(repository.completeTemplateUpdate(
                            retried,
                            "before",
                            "after",
                            Instant.ofEpochMilli(5_200L))
                    .toCompletableFuture().join());

            assertEquals(REVISION_TWO, appliedRevision(runtime, scenario));
            assertEquals(PendingMutationState.COMPLETED, mutationState(runtime, scenario));
            assertEquals(1, new SQLiteAuditRepository(runtime)
                    .listByAggregate(
                            "lore_instance",
                            scenario.instanceId().value().toString(),
                            PageRequest.first(10))
                    .toCompletableFuture().join().items().size());
            assertEquals(2, mutationAttempts(runtime, scenario));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void completesAfterRestartWhenThePhysicalItemAlreadyHasTheTargetRevision() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("resume.db"));
        try {
            Scenario scenario = seedRollout(runtime);
            SQLitePendingMutationRepository repository =
                    new SQLitePendingMutationRepository(runtime);
            PreparedTemplateUpdate update = prepare(repository, scenario, "worker-a");
            setAppliedRevision(runtime, scenario, REVISION_TWO);

            assertTrue(repository.completeTemplateUpdate(
                            update,
                            "target",
                            "target",
                            Instant.ofEpochMilli(5_100L))
                    .toCompletableFuture().join());
            assertEquals(REVISION_TWO, appliedRevision(runtime, scenario));
            assertEquals(PendingMutationState.COMPLETED, mutationState(runtime, scenario));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void unexpectedPhysicalRevisionMovesPendingWorkToReviewWithoutClaimingIt() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("mismatch.db"));
        try {
            Scenario scenario = seedRollout(runtime);
            SQLitePendingMutationRepository repository =
                    new SQLitePendingMutationRepository(runtime);

            TemplateUpdatePrepareResult result = repository.prepareTemplateUpdate(
                            scenario.identity(new TemplateRevision(3L)),
                            "worker-a",
                            CLAIM_TIME,
                            LEASE)
                    .toCompletableFuture().join();

            assertEquals(TemplateUpdatePrepareResult.Status.REVIEW_REQUIRED, result.status());
            assertEquals(PendingMutationState.REVIEW_REQUIRED, mutationState(runtime, scenario));
            assertEquals(0, mutationAttempts(runtime, scenario));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void completionAuditFailureRollsBackTheRevisionAndMutationState() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("rollback.db"));
        try {
            Scenario scenario = seedRollout(runtime);
            SQLitePendingMutationRepository repository =
                    new SQLitePendingMutationRepository(runtime);
            PreparedTemplateUpdate update = prepare(repository, scenario, "worker-a");
            installAuditFailureTrigger(runtime);

            assertThrows(
                    CompletionException.class,
                    () -> repository.completeTemplateUpdate(
                                    update,
                                    "before",
                                    "after",
                                    Instant.ofEpochMilli(5_100L))
                            .toCompletableFuture().join());

            assertEquals(REVISION_ONE, appliedRevision(runtime, scenario));
            assertEquals(PendingMutationState.CLAIMED, mutationState(runtime, scenario));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static PreparedTemplateUpdate prepare(
            SQLitePendingMutationRepository repository,
            Scenario scenario,
            String claimToken) {
        TemplateUpdatePrepareResult result = repository.prepareTemplateUpdate(
                        scenario.identity(REVISION_ONE),
                        claimToken,
                        CLAIM_TIME,
                        LEASE)
                .toCompletableFuture().join();
        assertEquals(TemplateUpdatePrepareResult.Status.PREPARED, result.status());
        assertEquals(2, result.preparedUpdate().targetTemplate().payload()[0]);
        return result.preparedUpdate();
    }

    private static Scenario seedRollout(SQLiteStorageRuntime runtime) {
        LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
        LoreInstanceId instanceId = new LoreInstanceId(UUID.randomUUID());
        new SQLiteDefinitionRepository(runtime).create(
                        new LoreDefinition(
                                definitionId,
                                new DefinitionKey("execution-" + definitionId.value()),
                                "Execution Test",
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

    private static PendingMutationState mutationState(
            SQLiteStorageRuntime runtime,
            Scenario scenario) {
        return runtime.execute(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT state FROM pending_mutations "
                                    + "WHERE instance_id = ? AND desired_revision = 2")) {
                        statement.setString(1, scenario.instanceId().value().toString());
                        try (ResultSet resultSet = statement.executeQuery()) {
                            assertTrue(resultSet.next());
                            return PendingMutationState.valueOf(resultSet.getString(1));
                        }
                    }
                })
                .toCompletableFuture().join();
    }

    private static int mutationAttempts(
            SQLiteStorageRuntime runtime,
            Scenario scenario) {
        return runtime.execute(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT attempt_count FROM pending_mutations "
                                    + "WHERE instance_id = ? AND desired_revision = 2")) {
                        statement.setString(1, scenario.instanceId().value().toString());
                        try (ResultSet resultSet = statement.executeQuery()) {
                            assertTrue(resultSet.next());
                            return resultSet.getInt(1);
                        }
                    }
                })
                .toCompletableFuture().join();
    }

    private static TemplateRevision appliedRevision(
            SQLiteStorageRuntime runtime,
            Scenario scenario) {
        return new SQLiteInstanceRepository(runtime).findById(scenario.instanceId())
                .toCompletableFuture().join().orElseThrow().appliedRevision();
    }

    private static void setAppliedRevision(
            SQLiteStorageRuntime runtime,
            Scenario scenario,
            TemplateRevision revision) {
        runtime.execute(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE lore_instances SET applied_revision = ? WHERE instance_id = ?")) {
                        statement.setLong(1, revision.value());
                        statement.setString(2, scenario.instanceId().value().toString());
                        statement.executeUpdate();
                    }
                    return null;
                })
                .toCompletableFuture().join();
    }

    private static void installAuditFailureTrigger(SQLiteStorageRuntime runtime) {
        runtime.execute(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "CREATE TRIGGER reject_template_completion_audit "
                                    + "BEFORE INSERT ON audit_events "
                                    + "WHEN NEW.event_type = 'template_update_completed' "
                                    + "BEGIN SELECT RAISE(ABORT, 'test audit failure'); END")) {
                        statement.executeUpdate();
                    }
                    return null;
                })
                .toCompletableFuture().join();
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
        private LoreItemIdentity identity(TemplateRevision revision) {
            return new LoreItemIdentity(definitionId, instanceId, revision);
        }
    }
}
