package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreDefinitionRevision;
import net.enthusia.loreitems.domain.LoreInstance;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.LoreInstanceLifecycle;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteTrackingObservationStoreTest {
    private static final LoreDefinitionId DEFINITION_ID = new LoreDefinitionId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final LoreInstanceId INSTANCE_ID = new LoreInstanceId(
            UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private static final TemplateRevision REVISION = new TemplateRevision(1);
    private static final LoreItemIdentity IDENTITY =
            new LoreItemIdentity(DEFINITION_ID, INSTANCE_ID, REVISION);
    private static final LocationDescriptor SLOT_ONE = new LocationDescriptor(
            LocationDescriptor.Type.PLAYER_INVENTORY,
            "player:33333333-3333-3333-3333-333333333333",
            "slot:1");
    private static final LocationDescriptor SLOT_TWO = new LocationDescriptor(
            LocationDescriptor.Type.PLAYER_INVENTORY,
            "player:33333333-3333-3333-3333-333333333333",
            "slot:2");
    private static final LocationDescriptor DROPPED = new LocationDescriptor(
            LocationDescriptor.Type.DROPPED_ITEM,
            "minecraft:overworld:entity:44444444-4444-4444-4444-444444444444:1:64:1",
            "item-entity");

    @TempDir
    Path temporaryDirectory;

    @Test
    void authoritativeMoveAndLastConfirmedPersistAcrossRestart() {
        Path database = temporaryDirectory.resolve("tracking.db");
        SQLiteStorageRuntime runtime = start(database);
        try {
            seedActiveInstance(runtime);
            SQLiteTrackingObservationStore store = new SQLiteTrackingObservationStore(runtime);

            assertEquals(
                    TrackingObservationUseCase.Status.RECORDED,
                    record(store, present(SLOT_ONE, reconciliation()), 1_000L).status());
            assertEquals(
                    TrackingObservationUseCase.Status.RECORDED,
                    record(store, present(DROPPED, authoritative()), 1_100L).status());
            assertEquals(
                    TrackingObservationUseCase.Status.RECORDED,
                    record(store, lastConfirmed(DROPPED), 1_200L).status());

            assertLastConfirmedDropped(runtime);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }

        runtime = start(database);
        try {
            assertLastConfirmedDropped(runtime);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void conservativeDifferentPathFencesDuplicateWithoutDeletingEitherCopy() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("duplicate.db"));
        try {
            seedActiveInstance(runtime);
            SQLiteTrackingObservationStore store = new SQLiteTrackingObservationStore(runtime);

            assertEquals(
                    TrackingObservationUseCase.Status.RECORDED,
                    record(store, present(SLOT_ONE, reconciliation()), 1_000L).status());
            assertEquals(
                    TrackingObservationUseCase.Status.CONFLICT_RECORDED,
                    record(store, present(SLOT_TWO, reconciliation()), 1_100L).status());

            InstanceCurrentState current = currentState(runtime);
            assertEquals(InstanceCurrentState.State.CONFLICTING, current.state());
            assertEquals(LocationDescriptor.Type.DUPLICATE_CONFLICT, current.location().type());
            assertEquals(LoreInstanceLifecycle.ACTIVE, new SQLiteInstanceRepository(runtime)
                    .findById(INSTANCE_ID).toCompletableFuture().join().orElseThrow().lifecycle());

            var observations = new SQLiteObservationRepository(runtime)
                    .listByInstance(INSTANCE_ID, PageRequest.first(20))
                    .toCompletableFuture().join().items();
            assertTrue(observations.stream().anyMatch(observation ->
                    SLOT_ONE.equals(observation.location())
                            && observation.confidence()
                                    == InstanceObservation.Confidence.CONFLICTING));
            assertTrue(observations.stream().anyMatch(observation ->
                    SLOT_TWO.equals(observation.location())
                            && observation.confidence()
                                    == InstanceObservation.Confidence.CONFLICTING));

            var anomalies = new SQLiteAnomalyRepository(runtime)
                    .listByInstance(INSTANCE_ID, PageRequest.first(10))
                    .toCompletableFuture().join().items();
            assertEquals(1, anomalies.size());
            assertEquals(InstanceAnomaly.Type.DUPLICATE_INSTANCE, anomalies.getFirst().type());
            assertEquals(InstanceAnomaly.Status.OPEN, anomalies.getFirst().status());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void failedAuditRollsBackObservationCurrentStateAndAnomaly() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("rollback.db"));
        try {
            seedActiveInstance(runtime);
            runtime.execute(connection -> {
                        try (var statement = connection.createStatement()) {
                            statement.execute("CREATE TRIGGER fail_tracking_audit "
                                    + "BEFORE INSERT ON audit_events "
                                    + "WHEN NEW.event_type = 'tracking_location_confirmed' "
                                    + "BEGIN SELECT RAISE(ABORT, 'forced audit failure'); END");
                        }
                        return null;
                    })
                    .toCompletableFuture().join();
            SQLiteTrackingObservationStore store = new SQLiteTrackingObservationStore(runtime);

            assertThrows(CompletionException.class, () ->
                    record(store, present(SLOT_ONE, reconciliation()), 1_000L));

            assertEquals(
                    InstanceCurrentState.State.MISSING_UNRESOLVED,
                    currentState(runtime).state());
            assertTrue(new SQLiteObservationRepository(runtime)
                    .listByInstance(INSTANCE_ID, PageRequest.first(10))
                    .toCompletableFuture().join().items().isEmpty());
            assertTrue(new SQLiteAnomalyRepository(runtime)
                    .listByInstance(INSTANCE_ID, PageRequest.first(10))
                    .toCompletableFuture().join().items().isEmpty());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static void assertLastConfirmedDropped(SQLiteStorageRuntime runtime) {
        InstanceCurrentState current = currentState(runtime);
        assertEquals(InstanceCurrentState.State.LAST_CONFIRMED, current.state());
        assertEquals(DROPPED, current.location());
        assertEquals(3L, current.stateRevision());
    }

    private static TrackingObservationUseCase.Result record(
            SQLiteTrackingObservationStore store,
            TrackingObservationUseCase.Request request,
            long observedAt) {
        return store.record(request, Instant.ofEpochMilli(observedAt))
                .toCompletableFuture().join();
    }

    private static TrackingObservationUseCase.Request present(
            LocationDescriptor location,
            TrackingObservationUseCase.EvidenceMode mode) {
        return new TrackingObservationUseCase.Request(
                IDENTITY,
                location,
                TrackingObservationUseCase.Presence.PRESENT,
                mode,
                "tracking-test");
    }

    private static TrackingObservationUseCase.Request lastConfirmed(
            LocationDescriptor location) {
        return new TrackingObservationUseCase.Request(
                IDENTITY,
                location,
                TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                reconciliation(),
                "tracking-test");
    }

    private static TrackingObservationUseCase.EvidenceMode reconciliation() {
        return TrackingObservationUseCase.EvidenceMode.RECONCILIATION;
    }

    private static TrackingObservationUseCase.EvidenceMode authoritative() {
        return TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION;
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

    private static InstanceCurrentState currentState(SQLiteStorageRuntime runtime) {
        return new SQLiteCurrentStateRepository(runtime).findByInstance(INSTANCE_ID)
                .toCompletableFuture().join().orElseThrow();
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
