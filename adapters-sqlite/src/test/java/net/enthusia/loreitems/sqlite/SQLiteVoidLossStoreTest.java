package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.PreparedVoidLoss;
import net.enthusia.loreitems.application.VoidLossUseCase;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreDefinitionRevision;
import net.enthusia.loreitems.domain.LoreInstance;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.LoreInstanceLifecycle;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteVoidLossStoreTest {
    private static final LoreDefinitionId DEFINITION_ID = new LoreDefinitionId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final LoreInstanceId INSTANCE_ID = new LoreInstanceId(
            UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private static final UUID ENTITY_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID MUTATION_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID CLAIM_TOKEN =
            UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final TemplateRevision REVISION = new TemplateRevision(1);
    private static final String LOCATION_KEY = "minecraft:overworld:12:-65:34";

    @TempDir
    Path temporaryDirectory;

    @Test
    void terminalLossCommitsInstanceObservationStateMutationAndAuditAcrossRestart() {
        Path database = temporaryDirectory.resolve("void.db");
        PreparedVoidLoss loss;
        SQLiteStorageRuntime runtime = start(database);
        try {
            seedActiveInstance(runtime);
            SQLiteVoidLossStore store = new SQLiteVoidLossStore(runtime);

            VoidLossUseCase.PrepareResult prepared = prepare(store, request());
            assertEquals(VoidLossUseCase.PrepareStatus.PREPARED, prepared.status());
            loss = prepared.prepared();
            assertEquals("CLAIMED", mutationState(runtime));
            assertTrue(store.complete(loss, Instant.ofEpochMilli(2_000L))
                    .toCompletableFuture().join());

            assertTerminalState(runtime);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }

        runtime = start(database);
        try {
            assertTerminalState(runtime);
            assertTrue(new SQLiteInstanceRepository(runtime).findById(INSTANCE_ID)
                    .toCompletableFuture().join().orElseThrow().lifecycle().terminal());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void unknownMismatchAndKnownAnomalyNeverCreateDestructiveIntent() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("blocked.db"));
        try {
            SQLiteVoidLossStore store = new SQLiteVoidLossStore(runtime);
            assertEquals(
                    VoidLossUseCase.PrepareStatus.UNKNOWN_INSTANCE,
                    prepare(store, request()).status());
            assertEquals(0, mutationCount(runtime));

            seedActiveInstance(runtime);
            VoidLossUseCase.Request mismatch = new VoidLossUseCase.Request(
                    new LoreItemIdentity(
                            new LoreDefinitionId(UUID.fromString(
                                    "99999999-9999-9999-9999-999999999999")),
                            INSTANCE_ID,
                            REVISION),
                    ENTITY_ID,
                    LOCATION_KEY);
            assertEquals(
                    VoidLossUseCase.PrepareStatus.IDENTITY_MISMATCH,
                    prepare(store, mismatch).status());
            assertEquals(0, mutationCount(runtime));

            new SQLiteAnomalyRepository(runtime).create(new InstanceAnomaly(
                            UUID.fromString("66666666-6666-6666-6666-666666666666"),
                            INSTANCE_ID,
                            DEFINITION_ID,
                            InstanceAnomaly.Type.DUPLICATE_INSTANCE,
                            InstanceAnomaly.Status.OPEN,
                            "Two credible copies are present.",
                            900L,
                            900L,
                            null,
                            null,
                            null,
                            null,
                            0L))
                    .toCompletableFuture().join();
            assertEquals(
                    VoidLossUseCase.PrepareStatus.REVIEW_REQUIRED,
                    prepare(store, request()).status());
            assertEquals(0, mutationCount(runtime));
            assertEquals(LoreInstanceLifecycle.ACTIVE, instance(runtime).lifecycle());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void abortKeepsPhysicalInstanceActiveAndReviewBlocksBlindRetry() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("abort-review.db"));
        try {
            seedActiveInstance(runtime);
            SQLiteVoidLossStore store = new SQLiteVoidLossStore(runtime);
            PreparedVoidLoss aborted = prepare(store, request()).prepared();

            assertTrue(store.abort(
                            aborted,
                            "Item rose above the minimum height.",
                            Instant.ofEpochMilli(1_500L))
                    .toCompletableFuture().join());
            assertEquals("COMPLETED", mutationState(runtime));
            assertEquals(LoreInstanceLifecycle.ACTIVE, instance(runtime).lifecycle());
            assertEquals(InstanceCurrentState.State.MISSING_UNRESOLVED,
                    currentState(runtime).state());

            PreparedVoidLoss review = prepareWithIds(
                    store,
                    request(),
                    UUID.fromString("77777777-7777-7777-7777-777777777777"),
                    UUID.fromString("88888888-8888-8888-8888-888888888888"))
                    .prepared();
            assertTrue(store.requireReview(
                            review,
                            "Entity vanished after durable preparation.",
                            Instant.ofEpochMilli(1_600L))
                    .toCompletableFuture().join());
            assertEquals("REVIEW_REQUIRED", mutationState(runtime, review.mutationId()));
            assertEquals(
                    VoidLossUseCase.PrepareStatus.REVIEW_REQUIRED,
                    prepareWithIds(
                                    store,
                                    request(),
                                    UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                                    UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
                            .status());
            assertEquals(LoreInstanceLifecycle.ACTIVE, instance(runtime).lifecycle());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void completionRollsBackEveryTerminalWriteWhenAuditFails() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("rollback.db"));
        try {
            seedActiveInstance(runtime);
            SQLiteVoidLossStore store = new SQLiteVoidLossStore(runtime);
            PreparedVoidLoss loss = prepare(store, request()).prepared();
            runtime.execute(connection -> {
                        try (var statement = connection.createStatement()) {
                            statement.execute("CREATE TRIGGER fail_void_completion_audit "
                                    + "BEFORE INSERT ON audit_events "
                                    + "WHEN NEW.event_type = 'void_loss_completed' "
                                    + "BEGIN SELECT RAISE(ABORT, 'forced audit failure'); END");
                        }
                        return null;
                    })
                    .toCompletableFuture().join();

            assertThrows(CompletionException.class, () -> store.complete(
                            loss, Instant.ofEpochMilli(2_000L))
                    .toCompletableFuture().join());

            assertEquals("CLAIMED", mutationState(runtime));
            assertEquals(LoreInstanceLifecycle.ACTIVE, instance(runtime).lifecycle());
            assertEquals(InstanceCurrentState.State.MISSING_UNRESOLVED,
                    currentState(runtime).state());
            assertTrue(new SQLiteObservationRepository(runtime)
                    .listByInstance(INSTANCE_ID, PageRequest.first(10))
                    .toCompletableFuture().join().items().isEmpty());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static VoidLossUseCase.PrepareResult prepare(
            SQLiteVoidLossStore store,
            VoidLossUseCase.Request request) {
        return prepareWithIds(store, request, MUTATION_ID, CLAIM_TOKEN);
    }

    private static VoidLossUseCase.PrepareResult prepareWithIds(
            SQLiteVoidLossStore store,
            VoidLossUseCase.Request request,
            UUID mutationId,
            UUID claimToken) {
        return store.prepare(
                        request,
                        mutationId,
                        claimToken,
                        Instant.ofEpochMilli(1_000L),
                        Instant.ofEpochMilli(31_000L))
                .toCompletableFuture().join();
    }

    private static VoidLossUseCase.Request request() {
        return new VoidLossUseCase.Request(
                new LoreItemIdentity(DEFINITION_ID, INSTANCE_ID, REVISION),
                ENTITY_ID,
                LOCATION_KEY);
    }

    private static void assertTerminalState(SQLiteStorageRuntime runtime) {
        LoreInstance stored = instance(runtime);
        assertEquals(LoreInstanceLifecycle.VOID_DESTROYED, stored.lifecycle());
        assertEquals(2_000L, stored.terminalAtEpochMillis());
        assertEquals("COMPLETED", mutationState(runtime));

        Page<InstanceObservation> observations = new SQLiteObservationRepository(runtime)
                .listByInstance(INSTANCE_ID, PageRequest.first(10))
                .toCompletableFuture().join();
        assertEquals(1, observations.items().size());
        InstanceObservation observation = observations.items().getFirst();
        assertEquals(InstanceObservation.Confidence.TERMINAL_VOID, observation.confidence());
        assertEquals(LOCATION_KEY, observation.location().locationKey());

        InstanceCurrentState current = currentState(runtime);
        assertEquals(InstanceCurrentState.State.TERMINAL_VOID, current.state());
        assertEquals(observation.observationId(), current.lastObservationId());
        assertEquals(1L, current.stateRevision());

        Page<AuditEventRecord> audits = new SQLiteAuditRepository(runtime)
                .listByAggregate(
                        "lore_instance",
                        INSTANCE_ID.value().toString(),
                        PageRequest.first(10))
                .toCompletableFuture().join();
        assertEquals(2, audits.items().size());
        assertEquals("void_loss_completed", audits.items().getFirst().eventType());
    }

    private static void seedActiveInstance(SQLiteStorageRuntime runtime) {
        new SQLiteDefinitionRepository(runtime).create(
                        new LoreDefinition(
                                DEFINITION_ID,
                                new DefinitionKey("vanguards_hourglass"),
                                "Vanguard's Hourglass",
                                REVISION,
                                500L,
                                null),
                        new LoreDefinitionRevision(
                                DEFINITION_ID,
                                REVISION,
                                1,
                                new byte[] {1, 2, 3},
                                500L))
                .toCompletableFuture().join();
        new SQLiteInstanceRepository(runtime).create(new LoreInstance(
                        INSTANCE_ID,
                        DEFINITION_ID,
                        REVISION,
                        REVISION,
                        LoreInstanceLifecycle.ACTIVE,
                        500L,
                        null))
                .toCompletableFuture().join();
        new SQLiteCurrentStateRepository(runtime).create(new InstanceCurrentState(
                        INSTANCE_ID,
                        InstanceCurrentState.State.MISSING_UNRESOLVED,
                        null,
                        null,
                        0L,
                        500L))
                .toCompletableFuture().join();
    }

    private static LoreInstance instance(SQLiteStorageRuntime runtime) {
        return new SQLiteInstanceRepository(runtime).findById(INSTANCE_ID)
                .toCompletableFuture().join().orElseThrow();
    }

    private static InstanceCurrentState currentState(SQLiteStorageRuntime runtime) {
        return new SQLiteCurrentStateRepository(runtime).findByInstance(INSTANCE_ID)
                .toCompletableFuture().join().orElseThrow();
    }

    private static String mutationState(SQLiteStorageRuntime runtime) {
        return mutationState(runtime, MUTATION_ID);
    }

    private static String mutationState(SQLiteStorageRuntime runtime, UUID mutationId) {
        return runtime.execute(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT state FROM pending_mutations WHERE mutation_id = ?")) {
                        statement.setString(1, mutationId.toString());
                        try (var resultSet = statement.executeQuery()) {
                            if (!resultSet.next()) {
                                throw new IllegalStateException("Expected void-loss mutation");
                            }
                            return resultSet.getString("state");
                        }
                    }
                })
                .toCompletableFuture().join();
    }

    private static int mutationCount(SQLiteStorageRuntime runtime) {
        return runtime.execute(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT COUNT(*) FROM pending_mutations WHERE mutation_type = ?")) {
                        statement.setString(1, SQLiteVoidLossStore.MUTATION_TYPE);
                        try (var resultSet = statement.executeQuery()) {
                            assertTrue(resultSet.next());
                            return resultSet.getInt(1);
                        }
                    }
                })
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
