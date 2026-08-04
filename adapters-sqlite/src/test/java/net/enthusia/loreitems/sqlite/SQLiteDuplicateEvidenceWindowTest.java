package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

class SQLiteDuplicateEvidenceWindowTest {
    private static final LoreDefinitionId DEFINITION_ID = new LoreDefinitionId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final LoreInstanceId INSTANCE_ID = new LoreInstanceId(
            UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private static final TemplateRevision REVISION = new TemplateRevision(1);
    private static final LoreItemIdentity IDENTITY =
            new LoreItemIdentity(DEFINITION_ID, INSTANCE_ID, REVISION);
    private static final LocationDescriptor SLOT_ONE = location("slot:1");
    private static final LocationDescriptor SLOT_TWO = location("slot:2");

    @TempDir
    Path temporaryDirectory;

    @Test
    void historicalObservationCannotResolveNewDuplicateOccurrence() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("window.db"));
        try {
            seed(runtime);
            SQLiteTrackingObservationStore tracking = new SQLiteTrackingObservationStore(runtime);
            SQLiteAnomalyRepository anomalies = new SQLiteAnomalyRepository(runtime);

            record(tracking, SLOT_ONE, 1_000L);
            record(tracking, SLOT_TWO, 1_100L);
            InstanceAnomaly first = activeAnomaly(anomalies);
            InstanceObservation oldChoice = observations(runtime).stream()
                    .filter(observation -> SLOT_ONE.equals(observation.location()))
                    .filter(observation -> observation.confidence()
                            == InstanceObservation.Confidence.CONFLICTING)
                    .findFirst()
                    .orElseThrow();
            assertEquals(
                    LoreItemsAdministrationUseCase.DuplicateResolutionStatus.RESOLVED,
                    anomalies.resolveDuplicate(
                                    request(first, oldChoice.observationId()),
                                    Instant.ofEpochMilli(1_200L))
                            .toCompletableFuture().join().status());

            record(tracking, SLOT_TWO, 2_000L);
            InstanceAnomaly second = activeAnomaly(anomalies);
            assertEquals(2_000L, second.firstSeenAtEpochMillis());

            LoreItemsAdministrationUseCase.DuplicateResolutionResult result =
                    anomalies.resolveDuplicate(
                                    request(second, oldChoice.observationId()),
                                    Instant.ofEpochMilli(2_100L))
                            .toCompletableFuture().join();

            assertEquals(
                    LoreItemsAdministrationUseCase.DuplicateResolutionStatus.INVALID_SELECTION,
                    result.status());
            assertEquals(
                    InstanceCurrentState.State.CONFLICTING,
                    new SQLiteCurrentStateRepository(runtime).findByInstance(INSTANCE_ID)
                            .toCompletableFuture().join().orElseThrow().state());
            assertEquals(
                    InstanceAnomaly.Status.OPEN,
                    anomalies.findById(second.anomalyId())
                            .toCompletableFuture().join().orElseThrow().status());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static LoreItemsAdministrationUseCase.DuplicateResolutionRequest request(
            InstanceAnomaly anomaly, long observationId) {
        return new LoreItemsAdministrationUseCase.DuplicateResolutionRequest(
                anomaly.anomalyId(), anomaly.stateRevision(), observationId, "test-admin");
    }

    private static InstanceAnomaly activeAnomaly(SQLiteAnomalyRepository anomalies) {
        return anomalies.listActive(PageRequest.first(10))
                .toCompletableFuture().join().items().getFirst();
    }

    private static java.util.List<InstanceObservation> observations(
            SQLiteStorageRuntime runtime) {
        return new SQLiteObservationRepository(runtime)
                .listByInstance(INSTANCE_ID, PageRequest.first(50))
                .toCompletableFuture().join().items();
    }

    private static void record(
            SQLiteTrackingObservationStore store,
            LocationDescriptor location,
            long observedAt) {
        store.record(new TrackingObservationUseCase.Request(
                        IDENTITY,
                        location,
                        TrackingObservationUseCase.Presence.PRESENT,
                        TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                        "evidence-window-test"),
                        Instant.ofEpochMilli(observedAt))
                .toCompletableFuture().join();
    }

    private static LocationDescriptor location(String path) {
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
