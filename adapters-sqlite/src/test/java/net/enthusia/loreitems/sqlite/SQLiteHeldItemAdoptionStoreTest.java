package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.HeldItemAdoptionPreparation;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.PrepareHeldItemAdoptionRequest;
import net.enthusia.loreitems.application.PreparedHeldItemAdoption;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreDefinitionRevision;
import net.enthusia.loreitems.domain.PendingMutationState;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteHeldItemAdoptionStoreTest {
    private static final DefinitionKey KEY = new DefinitionKey("vanguards_blade");
    private static final UUID PLAYER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MUTATION_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID INSTANCE_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CLAIM_TOKEN =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String BEFORE_FINGERPRINT = "a".repeat(64);
    private static final String AFTER_FINGERPRINT = "b".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void adoptionIntentAndExactInventoryObservationSurviveRestart() {
        Path database = temporaryDirectory.resolve("adoption.db");
        PreparedHeldItemAdoption adoption = prepareAndComplete(database);
        assertCompletedAfterRestart(database, adoption);
    }

    @Test
    void unknownDefinitionCreatesNoPartialDurableState() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("unknown.db"));
        try {
            SQLiteHeldItemAdoptionStore store = new SQLiteHeldItemAdoptionStore(runtime);

            Optional<PreparedHeldItemAdoption> result = store.prepare(preparation())
                    .toCompletableFuture().join();

            assertTrue(result.isEmpty());
            assertEquals(0, count(runtime, "lore_instances"));
            assertEquals(0, count(runtime, "instance_current_state"));
            assertEquals(0, count(runtime, "pending_mutations"));
            assertEquals(0, count(runtime, "audit_events"));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void preparationRollsBackInstanceStateAndClaimWhenAuditFails() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("rollback.db"));
        try {
            seedDefinition(runtime);
            runtime.execute(connection -> {
                        try (var statement = connection.createStatement()) {
                            statement.execute("CREATE TRIGGER fail_adoption_audit "
                                    + "BEFORE INSERT ON audit_events "
                                    + "BEGIN SELECT RAISE(ABORT, 'forced audit failure'); END");
                        }
                        return null;
                    })
                    .toCompletableFuture().join();
            SQLiteHeldItemAdoptionStore store = new SQLiteHeldItemAdoptionStore(runtime);

            assertThrows(CompletionException.class, () -> store.prepare(preparation())
                    .toCompletableFuture().join());

            assertEquals(0, count(runtime, "lore_instances"));
            assertEquals(0, count(runtime, "instance_current_state"));
            assertEquals(0, count(runtime, "pending_mutations"));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void expiredOrUnverifiableClaimIsPreservedForExplicitReview() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("review.db"));
        try {
            seedDefinition(runtime);
            SQLiteHeldItemAdoptionStore store = new SQLiteHeldItemAdoptionStore(runtime);
            PreparedHeldItemAdoption adoption = store.prepare(preparation())
                    .toCompletableFuture().join().orElseThrow();

            assertFalse(store.complete(
                            adoption, AFTER_FINGERPRINT, Instant.ofEpochMilli(31_001L))
                    .toCompletableFuture().join());
            assertTrue(store.requireReview(
                            adoption,
                            "Slot changed \"after\" preparation",
                            Instant.ofEpochMilli(31_002L))
                    .toCompletableFuture().join());

            MutationSnapshot mutation = mutation(runtime);
            assertEquals(PendingMutationState.REVIEW_REQUIRED, mutation.state());
            assertEquals(null, mutation.claimToken());
            InstanceCurrentState currentState = new SQLiteCurrentStateRepository(runtime)
                    .findByInstance(adoption.instanceId())
                    .toCompletableFuture().join().orElseThrow();
            assertEquals(InstanceCurrentState.State.MISSING_UNRESOLVED, currentState.state());
            assertTrue(new SQLiteObservationRepository(runtime)
                    .listByInstance(adoption.instanceId(), PageRequest.first(10))
                    .toCompletableFuture().join().items().isEmpty());
            Page<AuditEventRecord> audits = new SQLiteAuditRepository(runtime)
                    .listByAggregate(
                            "lore_instance",
                            adoption.instanceId().value().toString(),
                            PageRequest.first(10))
                    .toCompletableFuture().join();
            assertEquals("held_item_adoption_review_required",
                    audits.items().getFirst().eventType());
            assertTrue(audits.items().getFirst().detailJson().contains("\\\"after\\\""));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static PreparedHeldItemAdoption prepareAndComplete(Path database) {
        SQLiteStorageRuntime runtime = start(database);
        try {
            seedDefinition(runtime);
            SQLiteHeldItemAdoptionStore store = new SQLiteHeldItemAdoptionStore(runtime);
            PreparedHeldItemAdoption adoption = store.prepare(preparation())
                    .toCompletableFuture().join().orElseThrow();
            assertPrepared(runtime, adoption);
            assertTrue(store.complete(
                            adoption, AFTER_FINGERPRINT, Instant.ofEpochMilli(2_000L))
                    .toCompletableFuture().join());
            assertCompleted(runtime, adoption);
            return adoption;
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static void assertPrepared(
            SQLiteStorageRuntime runtime,
            PreparedHeldItemAdoption adoption) {
        assertEquals(new TemplateRevision(1), adoption.appliedRevision());
        assertEquals(INSTANCE_ID, adoption.instanceId().value());
        MutationSnapshot mutation = mutation(runtime);
        assertEquals(PendingMutationState.CLAIMED, mutation.state());
        assertEquals(CLAIM_TOKEN.toString(), mutation.claimToken());
        assertEquals(1, mutation.attemptCount());
        InstanceCurrentState currentState = new SQLiteCurrentStateRepository(runtime)
                .findByInstance(adoption.instanceId())
                .toCompletableFuture().join().orElseThrow();
        assertEquals(InstanceCurrentState.State.MISSING_UNRESOLVED, currentState.state());
        assertEquals(0L, currentState.stateRevision());
    }

    private static void assertCompleted(
            SQLiteStorageRuntime runtime,
            PreparedHeldItemAdoption adoption) {
        MutationSnapshot mutation = mutation(runtime);
        assertEquals(PendingMutationState.COMPLETED, mutation.state());
        assertEquals(null, mutation.claimToken());
        Page<InstanceObservation> observations = new SQLiteObservationRepository(runtime)
                .listByInstance(adoption.instanceId(), PageRequest.first(10))
                .toCompletableFuture().join();
        assertEquals(1, observations.items().size());
        InstanceObservation observation = observations.items().getFirst();
        assertEquals("player:" + PLAYER_ID, observation.location().locationKey());
        assertEquals("slot:4", observation.location().containerPath());
        InstanceCurrentState currentState = new SQLiteCurrentStateRepository(runtime)
                .findByInstance(adoption.instanceId())
                .toCompletableFuture().join().orElseThrow();
        assertEquals(InstanceCurrentState.State.CONFIRMED_NOW, currentState.state());
        assertEquals(observation.location(), currentState.location());
        assertEquals(observation.observationId(), currentState.lastObservationId());
        assertEquals(1L, currentState.stateRevision());
        Page<AuditEventRecord> audits = new SQLiteAuditRepository(runtime)
                .listByAggregate(
                            "lore_instance",
                            adoption.instanceId().value().toString(),
                            PageRequest.first(10))
                .toCompletableFuture().join();
        assertEquals(2, audits.items().size());
        assertEquals("held_item_adopted", audits.items().getFirst().eventType());
    }

    private static void assertCompletedAfterRestart(
            Path database,
            PreparedHeldItemAdoption adoption) {
        SQLiteStorageRuntime runtime = start(database);
        try {
            assertCompleted(runtime, adoption);
            assertEquals(adoption.definitionId(), new SQLiteInstanceRepository(runtime)
                    .findById(adoption.instanceId())
                    .toCompletableFuture().join().orElseThrow().definitionId());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static MutationSnapshot mutation(SQLiteStorageRuntime runtime) {
        return runtime.execute(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT state, claim_token, attempt_count FROM pending_mutations "
                                    + "WHERE mutation_id = ?")) {
                        statement.setString(1, MUTATION_ID.toString());
                        try (var resultSet = statement.executeQuery()) {
                            if (!resultSet.next()) {
                                throw new IllegalStateException("Expected adoption mutation");
                            }
                            return new MutationSnapshot(
                                    PendingMutationState.valueOf(resultSet.getString("state")),
                                    resultSet.getString("claim_token"),
                                    resultSet.getInt("attempt_count"));
                        }
                    }
                })
                .toCompletableFuture().join();
    }

    private static HeldItemAdoptionPreparation preparation() {
        return new HeldItemAdoptionPreparation(
                new PrepareHeldItemAdoptionRequest(
                        KEY, PLAYER_ID, 4, BEFORE_FINGERPRINT),
                MUTATION_ID,
                INSTANCE_ID,
                CLAIM_TOKEN,
                1_000L,
                31_000L);
    }

    private static void seedDefinition(SQLiteStorageRuntime runtime) {
        LoreDefinitionId definitionId = new LoreDefinitionId(UUID.fromString(
                "77777777-7777-7777-7777-777777777777"));
        new SQLiteDefinitionRepository(runtime).create(
                        new LoreDefinition(
                                definitionId,
                                KEY,
                                "Vanguard's Blade",
                                new TemplateRevision(1),
                                500L,
                                null),
                        new LoreDefinitionRevision(
                                definitionId,
                                new TemplateRevision(1),
                                1,
                                new byte[] {1, 2, 3},
                                500L))
                .toCompletableFuture().join();
    }

    private static int count(SQLiteStorageRuntime runtime, String table) {
        return runtime.execute(connection -> {
                    String sql = switch (table) {
                        case "lore_instances" -> "SELECT COUNT(*) FROM lore_instances";
                        case "instance_current_state" ->
                                "SELECT COUNT(*) FROM instance_current_state";
                        case "pending_mutations" -> "SELECT COUNT(*) FROM pending_mutations";
                        case "audit_events" -> "SELECT COUNT(*) FROM audit_events";
                        default -> throw new IllegalArgumentException("Unsupported table " + table);
                    };
                    try (PreparedStatement statement = connection.prepareStatement(sql); // nosemgrep
                         var resultSet = statement.executeQuery()) {
                        return resultSet.getInt(1);
                    }
                })
                .toCompletableFuture().join();
    }

    private record MutationSnapshot(
            PendingMutationState state,
            String claimToken,
            int attemptCount) {
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
