package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.InstanceCurrentState;
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

class SQLiteTrackingMovementTest {
    private static final LoreDefinitionId DEFINITION_ID = new LoreDefinitionId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final LoreInstanceId INSTANCE_ID = new LoreInstanceId(
            UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private static final TemplateRevision REVISION = new TemplateRevision(1);
    private static final LoreItemIdentity IDENTITY =
            new LoreItemIdentity(DEFINITION_ID, INSTANCE_ID, REVISION);
    private static final UUID ENTITY_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");

    @TempDir
    Path temporaryDirectory;

    @Test
    void movingSameDroppedEntityDoesNotCreateDuplicate() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("moving-drop.db"));
        try {
            seed(runtime);
            SQLiteTrackingObservationStore store = new SQLiteTrackingObservationStore(runtime);
            LocationDescriptor first = dropped(1, 64, 1);
            LocationDescriptor second = dropped(2, 64, 1);

            assertEquals(TrackingObservationUseCase.Status.RECORDED,
                    record(store, first, 1_000L).status());
            assertEquals(TrackingObservationUseCase.Status.RECORDED,
                    record(store, second, 1_100L).status());

            assertMovedWithoutAnomaly(runtime, second);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void movingSameArmorStandDoesNotCreateDuplicate() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("moving-armor-stand.db"));
        try {
            seed(runtime);
            SQLiteTrackingObservationStore store = new SQLiteTrackingObservationStore(runtime);
            LocationDescriptor first = armorStand(1, 64, 1);
            LocationDescriptor second = armorStand(2, 63, 1);

            assertEquals(TrackingObservationUseCase.Status.RECORDED,
                    record(store, first, 1_000L).status());
            assertEquals(TrackingObservationUseCase.Status.RECORDED,
                    record(store, second, 1_100L).status());

            assertMovedWithoutAnomaly(runtime, second);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void repeatedConflictLocationDoesNotGrowEvidence() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("deduplicate.db"));
        try {
            seed(runtime);
            SQLiteTrackingObservationStore store = new SQLiteTrackingObservationStore(runtime);
            LocationDescriptor first = player("slot:1");
            LocationDescriptor second = player("slot:2");

            record(store, first, 1_000L);
            assertEquals(TrackingObservationUseCase.Status.CONFLICT_RECORDED,
                    record(store, second, 1_100L).status());
            int observations = observationCount(runtime);
            long anomalyRevision = new SQLiteAnomalyRepository(runtime)
                    .listActive(PageRequest.first(10)).toCompletableFuture().join()
                    .items().getFirst().stateRevision();

            assertEquals(TrackingObservationUseCase.Status.UNCHANGED,
                    record(store, second, 1_200L).status());
            assertEquals(observations, observationCount(runtime));
            assertEquals(anomalyRevision, new SQLiteAnomalyRepository(runtime)
                    .listActive(PageRequest.first(10)).toCompletableFuture().join()
                    .items().getFirst().stateRevision());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static TrackingObservationUseCase.Result record(
            SQLiteTrackingObservationStore store,
            LocationDescriptor location,
            long observedAt) {
        return store.record(new TrackingObservationUseCase.Request(
                        IDENTITY,
                        location,
                        TrackingObservationUseCase.Presence.PRESENT,
                        TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                        "movement-test"),
                        Instant.ofEpochMilli(observedAt))
                .toCompletableFuture().join();
    }

    private static void assertMovedWithoutAnomaly(
            SQLiteStorageRuntime runtime,
            LocationDescriptor expected) {
        InstanceCurrentState current = new SQLiteCurrentStateRepository(runtime)
                .findByInstance(INSTANCE_ID).toCompletableFuture().join().orElseThrow();
        assertEquals(InstanceCurrentState.State.CONFIRMED_NOW, current.state());
        assertEquals(expected, current.location());
        assertTrue(new SQLiteAnomalyRepository(runtime)
                .listByInstance(INSTANCE_ID, PageRequest.first(10))
                .toCompletableFuture().join().items().isEmpty());
    }

    private static int observationCount(SQLiteStorageRuntime runtime) {
        return new SQLiteObservationRepository(runtime)
                .listByInstance(INSTANCE_ID, PageRequest.first(50))
                .toCompletableFuture().join().items().size();
    }

    private static LocationDescriptor dropped(int x, int y, int z) {
        return new LocationDescriptor(
                LocationDescriptor.Type.DROPPED_ITEM,
                "minecraft:overworld:entity:" + ENTITY_ID + ':' + x + ':' + y + ':' + z,
                "item-entity");
    }

    private static LocationDescriptor armorStand(int x, int y, int z) {
        return new LocationDescriptor(
                LocationDescriptor.Type.ARMOR_STAND,
                "minecraft:overworld:" + x + ':' + y + ':' + z + ':' + ENTITY_ID,
                "slot:head");
    }

    private static LocationDescriptor player(String path) {
        return new LocationDescriptor(
                LocationDescriptor.Type.PLAYER_INVENTORY,
                "player:33333333-3333-3333-3333-333333333333",
                path);
    }

    private static void seed(SQLiteStorageRuntime runtime) {
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
