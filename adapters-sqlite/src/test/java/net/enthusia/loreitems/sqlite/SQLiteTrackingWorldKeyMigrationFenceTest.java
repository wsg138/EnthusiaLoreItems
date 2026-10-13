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

/** Actual persisted legacy-name ambiguity safety; no automatic world remapping. */
class SQLiteTrackingWorldKeyMigrationFenceTest {
    private static final LoreDefinitionId DEFINITION_ID = new LoreDefinitionId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final LoreInstanceId INSTANCE_ID = new LoreInstanceId(
            UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private static final TemplateRevision REVISION = new TemplateRevision(1);
    private static final LoreItemIdentity IDENTITY =
            new LoreItemIdentity(DEFINITION_ID, INSTANCE_ID, REVISION);

    @TempDir
    Path temporaryDirectory;

    @Test
    void historicalWorldNameMoveIsFencedWithoutClaimingDuplicateAndSurvivesRestart() {
        Path database = temporaryDirectory.resolve("historical-key-review.db");
        LocationDescriptor legacy = new LocationDescriptor(
                LocationDescriptor.Type.BLOCK_CONTAINER, "minecraft:world:20:70:0", "slot:0");
        LocationDescriptor modern = new LocationDescriptor(
                LocationDescriptor.Type.BLOCK_CONTAINER,
                "block-v2:11111111-2222-3333-4444-555555555555:20:70:0", "slot:0");
        SQLiteStorageRuntime runtime = start(database);
        try {
            seedActiveInstance(runtime);
            SQLiteTrackingObservationStore store = new SQLiteTrackingObservationStore(runtime);
            assertEquals(TrackingObservationUseCase.Status.RECORDED,
                    record(store, present(legacy, reconciliation()), 1_000L).status());
            assertEquals(TrackingObservationUseCase.Status.CONFLICT_RECORDED,
                    record(store, present(modern, authoritative()), 1_100L).status());
            InstanceCurrentState current = currentState(runtime);
            assertEquals(InstanceCurrentState.State.CONFLICTING, current.state());
            assertEquals(LocationDescriptor.Type.DUPLICATE_CONFLICT, current.location().type());
            var observations = new SQLiteObservationRepository(runtime)
                    .listByInstance(INSTANCE_ID, PageRequest.first(20))
                    .toCompletableFuture().join().items();
            assertTrue(observations.stream().anyMatch(observation ->
                    legacy.equals(observation.location())));
            assertTrue(observations.stream().anyMatch(observation ->
                    modern.equals(observation.location())));
            // Old world names are ambiguous, not evidence of a second physical copy.
            assertTrue(new SQLiteAnomalyRepository(runtime)
                    .listByInstance(INSTANCE_ID, PageRequest.first(10))
                    .toCompletableFuture().join().items().isEmpty());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
        runtime = start(database);
        try {
            assertEquals(InstanceCurrentState.State.CONFLICTING, currentState(runtime).state());
            assertEquals(LocationDescriptor.Type.DUPLICATE_CONFLICT,
                    currentState(runtime).location().type());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void modernUuidWorldMovesStayAuthoritativeWithoutLegacyConflict() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("modern-worlds.db"));
        try {
            seedActiveInstance(runtime);
            SQLiteTrackingObservationStore store = new SQLiteTrackingObservationStore(runtime);
            LocationDescriptor first = new LocationDescriptor(
                    LocationDescriptor.Type.BLOCK_CONTAINER,
                    "block-v2:11111111-2222-3333-4444-555555555555:20:70:0", "slot:0");
            LocationDescriptor second = new LocationDescriptor(
                    LocationDescriptor.Type.BLOCK_CONTAINER,
                    "block-v2:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee:20:70:0", "slot:0");
            assertEquals(TrackingObservationUseCase.Status.RECORDED,
                    record(store, present(first, reconciliation()), 1_000L).status());
            assertEquals(TrackingObservationUseCase.Status.RECORDED,
                    record(store, present(second, authoritative()), 1_100L).status());
            assertEquals(InstanceCurrentState.State.CONFIRMED_NOW, currentState(runtime).state());
            assertEquals(second, currentState(runtime).location());
            assertTrue(new SQLiteAnomalyRepository(runtime)
                    .listByInstance(INSTANCE_ID, PageRequest.first(10))
                    .toCompletableFuture().join().items().isEmpty());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void historicalNestedContainerNeverAutoMapsToModernUuidRoot() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("nested-legacy-review.db"));
        try {
            seedActiveInstance(runtime);
            SQLiteTrackingObservationStore store = new SQLiteTrackingObservationStore(runtime);
            LocationDescriptor legacy = new LocationDescriptor(
                    LocationDescriptor.Type.NESTED_CONTAINER,
                    "root:BLOCK_CONTAINER:minecraft:world:14:70:0", "slot:0/shulker:0");
            LocationDescriptor modern = new LocationDescriptor(
                    LocationDescriptor.Type.NESTED_CONTAINER,
                    "root:BLOCK_CONTAINER:block-v2:11111111-2222-3333-4444-555555555555:14:70:0",
                    "slot:0/shulker:0");
            record(store, present(legacy, reconciliation()), 1_000L);
            assertEquals(TrackingObservationUseCase.Status.CONFLICT_RECORDED,
                    record(store, present(modern, authoritative()), 1_100L).status());
            assertEquals(InstanceCurrentState.State.CONFLICTING, currentState(runtime).state());
            assertTrue(new SQLiteAnomalyRepository(runtime)
                    .listByInstance(INSTANCE_ID, PageRequest.first(10))
                    .toCompletableFuture().join().items().isEmpty());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static TrackingObservationUseCase.Result record(
            SQLiteTrackingObservationStore store,
            TrackingObservationUseCase.Request request,
            long observedAt) {
        return store.record(request, Instant.ofEpochMilli(observedAt))
                .toCompletableFuture().join();
    }

    private static TrackingObservationUseCase.Request present(
            LocationDescriptor location, TrackingObservationUseCase.EvidenceMode mode) {
        return new TrackingObservationUseCase.Request(
                IDENTITY, location, TrackingObservationUseCase.Presence.PRESENT,
                mode, "migration-fence-test");
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
