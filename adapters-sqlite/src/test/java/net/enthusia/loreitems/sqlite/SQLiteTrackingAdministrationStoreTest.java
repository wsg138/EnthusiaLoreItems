package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.LoreItemsAdministrationUseCase;
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

class SQLiteTrackingAdministrationStoreTest {
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

    @TempDir
    Path temporaryDirectory;

    @Test
    void selectedLocationResolutionIsAtomicAndPreservesEvidence() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("resolve.db"));
        try {
            seed(runtime);
            createConflict(runtime);
            SQLiteAnomalyRepository anomalies = new SQLiteAnomalyRepository(runtime);
            InstanceAnomaly anomaly = anomalies.listByInstance(INSTANCE_ID, PageRequest.first(10))
                    .toCompletableFuture().join().items().getFirst();
            InstanceObservation selected = observations(runtime).stream()
                    .filter(observation -> SLOT_ONE.equals(observation.location()))
                    .filter(observation -> observation.confidence()
                            == InstanceObservation.Confidence.CONFLICTING)
                    .findFirst().orElseThrow();

            LoreItemsAdministrationUseCase.DuplicateResolutionResult result =
                    anomalies.resolveDuplicate(
                                    new LoreItemsAdministrationUseCase.DuplicateResolutionRequest(
                                            anomaly.anomalyId(),
                                            anomaly.stateRevision(),
                                            selected.observationId(),
                                            "test-admin"),
                                    Instant.ofEpochMilli(2_000L))
                            .toCompletableFuture().join();

            assertEquals(
                    LoreItemsAdministrationUseCase.DuplicateResolutionStatus.RESOLVED,
                    result.status());
            InstanceCurrentState current = current(runtime);
            assertEquals(InstanceCurrentState.State.CONFIRMED_NOW, current.state());
            assertEquals(SLOT_ONE, current.location());
            InstanceAnomaly resolved = anomalies.findById(anomaly.anomalyId())
                    .toCompletableFuture().join().orElseThrow();
            assertEquals(InstanceAnomaly.Status.RESOLVED, resolved.status());
            assertTrue(observations(runtime).stream().anyMatch(observation ->
                    SLOT_ONE.equals(observation.location())
                            && observation.confidence()
                                    == InstanceObservation.Confidence.CONFIRMED_NOW));
            assertTrue(observations(runtime).stream().anyMatch(observation ->
                    SLOT_TWO.equals(observation.location())
                            && observation.confidence()
                                    == InstanceObservation.Confidence.CONFLICTING));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void staleAnomalyRevisionDoesNotChangeConflictState() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("stale.db"));
        try {
            seed(runtime);
            createConflict(runtime);
            SQLiteAnomalyRepository anomalies = new SQLiteAnomalyRepository(runtime);
            InstanceAnomaly anomaly = anomalies.listByInstance(INSTANCE_ID, PageRequest.first(10))
                    .toCompletableFuture().join().items().getFirst();
            InstanceObservation selected = observations(runtime).stream()
                    .filter(observation -> SLOT_ONE.equals(observation.location()))
                    .findFirst().orElseThrow();

            LoreItemsAdministrationUseCase.DuplicateResolutionResult result =
                    anomalies.resolveDuplicate(
                                    new LoreItemsAdministrationUseCase.DuplicateResolutionRequest(
                                            anomaly.anomalyId(),
                                            anomaly.stateRevision() + 1,
                                            selected.observationId(),
                                            "test-admin"),
                                    Instant.ofEpochMilli(2_000L))
                            .toCompletableFuture().join();

            assertEquals(
                    LoreItemsAdministrationUseCase.DuplicateResolutionStatus.STALE,
                    result.status());
            assertEquals(InstanceCurrentState.State.CONFLICTING, current(runtime).state());
            assertEquals(InstanceAnomaly.Status.OPEN, anomalies.findById(anomaly.anomalyId())
                    .toCompletableFuture().join().orElseThrow().status());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static void createConflict(SQLiteStorageRuntime runtime) {
        SQLiteTrackingObservationStore store = new SQLiteTrackingObservationStore(runtime);
        store.record(present(SLOT_ONE), Instant.ofEpochMilli(1_000L))
                .toCompletableFuture().join();
        store.record(present(SLOT_TWO), Instant.ofEpochMilli(1_100L))
                .toCompletableFuture().join();
    }

    private static TrackingObservationUseCase.Request present(LocationDescriptor location) {
        return new TrackingObservationUseCase.Request(
                IDENTITY,
                location,
                TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "tracking-admin-test");
    }

    private static java.util.List<InstanceObservation> observations(
            SQLiteStorageRuntime runtime) {
        return new SQLiteObservationRepository(runtime)
                .listByInstance(INSTANCE_ID, PageRequest.first(20))
                .toCompletableFuture().join().items();
    }

    private static InstanceCurrentState current(SQLiteStorageRuntime runtime) {
        return new SQLiteCurrentStateRepository(runtime).findByInstance(INSTANCE_ID)
                .toCompletableFuture().join().orElseThrow();
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
