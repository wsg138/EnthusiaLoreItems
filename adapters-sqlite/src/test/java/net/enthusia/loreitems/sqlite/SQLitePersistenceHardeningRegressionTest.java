package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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

class SQLitePersistenceHardeningRegressionTest {
    private static final LoreDefinitionId DEFINITION_ID = new LoreDefinitionId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final LoreInstanceId INSTANCE_ID = new LoreInstanceId(
            UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private static final TemplateRevision REVISION = new TemplateRevision(1);
    private static final LoreItemIdentity IDENTITY =
            new LoreItemIdentity(DEFINITION_ID, INSTANCE_ID, REVISION);
    private static final LocationDescriptor DISPLAY_ONE = new LocationDescriptor(
            LocationDescriptor.Type.ITEM_DISPLAY,
            "minecraft:overworld:2:64:3:33333333-3333-3333-3333-333333333333",
            "item");
    private static final LocationDescriptor DISPLAY_TWO = new LocationDescriptor(
            LocationDescriptor.Type.ITEM_DISPLAY,
            "minecraft:overworld:9:70:9:44444444-4444-4444-4444-444444444444",
            "item");

    @TempDir
    Path temporaryDirectory;

    @Test
    void itemDisplayEvidencePersistsAndCanBeSelectedToResolveDuplicate() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("item-display.db"));
        try {
            seedActiveInstance(runtime);
            SQLiteTrackingObservationStore tracking = new SQLiteTrackingObservationStore(runtime);
            TrackingObservationUseCase.Result recorded = tracking.record(
                            new TrackingObservationUseCase.Request(
                                    IDENTITY,
                                    DISPLAY_ONE,
                                    TrackingObservationUseCase.Presence.PRESENT,
                                    TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                                    "item-display-reconciliation"),
                            Instant.ofEpochMilli(1_000L))
                    .toCompletableFuture().join();
            assertEquals(TrackingObservationUseCase.Status.RECORDED, recorded.status());
            assertEquals(DISPLAY_ONE, current(runtime).location());

            TrackingObservationUseCase.Result conflict = tracking.record(
                            new TrackingObservationUseCase.Request(
                                    IDENTITY,
                                    DISPLAY_TWO,
                                    TrackingObservationUseCase.Presence.PRESENT,
                                    TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                                    "item-display-reconciliation"),
                            Instant.ofEpochMilli(1_100L))
                    .toCompletableFuture().join();
            assertEquals(TrackingObservationUseCase.Status.CONFLICT_RECORDED, conflict.status());

            SQLiteAnomalyRepository anomalies = new SQLiteAnomalyRepository(runtime);
            InstanceAnomaly anomaly = anomalies.listByInstance(INSTANCE_ID, PageRequest.first(10))
                    .toCompletableFuture().join().items().getFirst();
            InstanceObservation selected = observations(runtime).stream()
                    .filter(observation -> DISPLAY_ONE.equals(observation.location()))
                    .filter(observation -> observation.confidence()
                            == InstanceObservation.Confidence.CONFLICTING)
                    .findFirst().orElseThrow();

            LoreItemsAdministrationUseCase.DuplicateResolutionResult resolution =
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
                    resolution.status());
            assertEquals(DISPLAY_ONE, current(runtime).location());
            assertEquals(
                    InstanceAnomaly.Status.RESOLVED,
                    anomalies.findById(anomaly.anomalyId())
                            .toCompletableFuture().join().orElseThrow().status());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void sameNameWeakenedInvariantTriggerFailsSchemaHealthVerification() throws SQLException {
        Path database = temporaryDirectory.resolve("weakened-trigger.db");
        SQLiteConnectionFactory factory = new SQLiteConnectionFactory(database, 5_000);
        try (Connection connection = factory.open()) {
            new MigrationRunner().migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER canonicalize_player_inventory_observation_insert");
                statement.execute(
                        "CREATE TRIGGER canonicalize_player_inventory_observation_insert "
                                + "AFTER INSERT ON instance_observations BEGIN SELECT 1; END");
            }
        }

        try (Connection connection = factory.open()) {
            SQLException failure = assertThrows(
                    SQLException.class, () -> new MigrationRunner().migrate(connection));
            assertTrue(failure.getMessage().contains("invalid required trigger"));
            assertTrue(failure.getMessage()
                    .contains("canonicalize_player_inventory_observation_insert"));
        }
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
