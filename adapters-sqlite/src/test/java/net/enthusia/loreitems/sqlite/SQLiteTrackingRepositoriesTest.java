package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
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

class SQLiteTrackingRepositoriesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsBoundedObservationsAndCompareAndSetCurrentStateAcrossRestart() {
        Path database = temporaryDirectory.resolve("tracking.db");
        TrackingIdentity identity = trackingIdentity();
        ObservationScenario observations = exerciseTrackingRuntime(database, identity);
        assertTrackingStateAfterRestart(database, identity.instance(), observations);
    }

    @Test
    void enforcesActiveAnomalyUniquenessAndLifecycleWithBoundedHistory() {
        Path database = temporaryDirectory.resolve("anomalies.db");
        TrackingIdentity identity = trackingIdentity();
        UUID firstAnomalyId = exerciseAnomalyRuntime(database, identity);
        assertResolvedAnomalyAfterRestart(database, firstAnomalyId);
    }

    private static TrackingIdentity trackingIdentity() {
        LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
        return new TrackingIdentity(definitionId, instance(definitionId));
    }

    private static ObservationScenario exerciseTrackingRuntime(
            Path database, TrackingIdentity identity) {
        SQLiteStorageRuntime runtime = start(database);
        try {
            seed(runtime, identity.definitionId(), identity.instance());
            SQLiteObservationRepository observations = new SQLiteObservationRepository(runtime);
            SQLiteCurrentStateRepository currentStates = new SQLiteCurrentStateRepository(runtime);
            ObservationScenario scenario = appendObservations(observations, identity);
            assertObservationPages(observations, identity.instance(), scenario);
            assertCurrentStateFencing(currentStates, identity.instance(), scenario);
            assertWrongDefinitionRejected(observations, identity, scenario.playerLocation());
            return scenario;
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static ObservationScenario appendObservations(
            SQLiteObservationRepository observations, TrackingIdentity identity) {
        LocationDescriptor playerLocation = new LocationDescriptor(
                LocationDescriptor.Type.PLAYER_INVENTORY,
                "player:" + UUID.randomUUID() + ":slot:4",
                null);
        InstanceObservation first = observations.append(new InstanceObservation(
                        0L,
                        identity.instance().id(),
                        identity.definitionId(),
                        playerLocation,
                        InstanceObservation.Confidence.CONFIRMED_NOW,
                        "player-inventory-close",
                        100L))
                .toCompletableFuture().join();
        InstanceObservation conflicting = observations.append(new InstanceObservation(
                        0L,
                        identity.instance().id(),
                        identity.definitionId(),
                        new LocationDescriptor(
                                LocationDescriptor.Type.BLOCK_CONTAINER,
                                "world:0,64,0",
                                "slot:2/shulker:slot:5"),
                        InstanceObservation.Confidence.CONFLICTING,
                        "chunk-load",
                        200L))
                .toCompletableFuture().join();
        return new ObservationScenario(playerLocation, first, conflicting);
    }

    private static void assertObservationPages(
            SQLiteObservationRepository observations,
            LoreInstance instance,
            ObservationScenario scenario) {
        Page<InstanceObservation> firstPage = observations
                .listByInstance(instance.id(), PageRequest.first(1))
                .toCompletableFuture().join();
        Page<InstanceObservation> secondPage = observations
                .listByInstance(instance.id(), new PageRequest(1, 1))
                .toCompletableFuture().join();
        assertEquals(1, firstPage.items().size());
        assertTrue(firstPage.hasMore());
        assertEquals(
                scenario.conflicting().observationId(),
                firstPage.items().getFirst().observationId());
        assertEquals(1, secondPage.items().size());
        assertFalse(secondPage.hasMore());
        Page<InstanceObservation> byLocation = observations.listByLocation(
                        LocationDescriptor.Type.PLAYER_INVENTORY,
                        scenario.playerLocation().locationKey(),
                        PageRequest.first(2))
                .toCompletableFuture().join();
        assertEquals(1, byLocation.items().size());
    }

    private static void assertCurrentStateFencing(
            SQLiteCurrentStateRepository currentStates,
            LoreInstance instance,
            ObservationScenario scenario) {
        InstanceCurrentState initial = new InstanceCurrentState(
                instance.id(),
                InstanceCurrentState.State.CONFIRMED_NOW,
                scenario.playerLocation(),
                scenario.first().observationId(),
                0L,
                100L);
        currentStates.create(initial).toCompletableFuture().join();
        assertThrows(
                CompletionException.class,
                () -> currentStates.create(initial).toCompletableFuture().join());
        InstanceCurrentState conflicting = new InstanceCurrentState(
                instance.id(),
                InstanceCurrentState.State.CONFLICTING,
                scenario.conflicting().location(),
                scenario.conflicting().observationId(),
                1L,
                201L);
        assertTrue(currentStates.compareAndSet(instance.id(), 0L, conflicting)
                .toCompletableFuture().join());
        assertFalse(currentStates.compareAndSet(instance.id(), 0L, conflicting)
                .toCompletableFuture().join());
    }

    private static void assertWrongDefinitionRejected(
            SQLiteObservationRepository observations,
            TrackingIdentity identity,
            LocationDescriptor location) {
        LoreDefinitionId wrongDefinition = new LoreDefinitionId(UUID.randomUUID());
        assertThrows(CompletionException.class, () -> observations.append(new InstanceObservation(
                        0L,
                        identity.instance().id(),
                        wrongDefinition,
                        location,
                        InstanceObservation.Confidence.CONFIRMED_NOW,
                        "invalid-definition",
                        300L))
                .toCompletableFuture().join());
    }

    private static void assertTrackingStateAfterRestart(
            Path database, LoreInstance instance, ObservationScenario scenario) {
        SQLiteStorageRuntime runtime = start(database);
        try {
            InstanceCurrentState restored = new SQLiteCurrentStateRepository(runtime)
                    .findByInstance(instance.id()).toCompletableFuture().join().orElseThrow();
            assertEquals(InstanceCurrentState.State.CONFLICTING, restored.state());
            assertEquals(scenario.conflicting().observationId(), restored.lastObservationId());
            assertEquals(1L, restored.stateRevision());
            InstanceObservation restoredObservation = new SQLiteObservationRepository(runtime)
                    .findById(scenario.first().observationId())
                    .toCompletableFuture().join().orElseThrow();
            assertEquals(scenario.first(), restoredObservation);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static UUID exerciseAnomalyRuntime(Path database, TrackingIdentity identity) {
        SQLiteStorageRuntime runtime = start(database);
        UUID firstAnomalyId = UUID.randomUUID();
        try {
            seed(runtime, identity.definitionId(), identity.instance());
            SQLiteAnomalyRepository anomalies = new SQLiteAnomalyRepository(runtime);
            createFirstAnomaly(anomalies, identity, firstAnomalyId);
            assertDuplicateActiveAnomalyRejected(anomalies, identity);
            exerciseAnomalyLifecycle(anomalies, firstAnomalyId);
            createReplacementAnomalies(anomalies, identity);
            assertAnomalyPages(anomalies, identity.instance());
            return firstAnomalyId;
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static void createFirstAnomaly(
            SQLiteAnomalyRepository anomalies,
            TrackingIdentity identity,
            UUID anomalyId) {
        anomalies.create(openAnomaly(
                        anomalyId,
                        identity.instance().id(),
                        identity.definitionId(),
                        InstanceAnomaly.Type.DUPLICATE_INSTANCE,
                        "Two credible locations",
                        100L))
                .toCompletableFuture().join();
    }

    private static void assertDuplicateActiveAnomalyRejected(
            SQLiteAnomalyRepository anomalies, TrackingIdentity identity) {
        assertThrows(CompletionException.class, () -> anomalies.create(openAnomaly(
                        UUID.randomUUID(),
                        identity.instance().id(),
                        identity.definitionId(),
                        InstanceAnomaly.Type.DUPLICATE_INSTANCE,
                        "Same active identity",
                        110L))
                .toCompletableFuture().join());
    }

    private static void exerciseAnomalyLifecycle(
            SQLiteAnomalyRepository anomalies, UUID anomalyId) {
        assertTrue(anomalies.refresh(anomalyId, 0L, "Three credible locations", 120L)
                .toCompletableFuture().join());
        assertFalse(anomalies.refresh(anomalyId, 0L, "Stale refresh", 130L)
                .toCompletableFuture().join());
        assertTrue(anomalies.acknowledge(anomalyId, 1L, "staff:operator", 140L)
                .toCompletableFuture().join());
        assertTrue(anomalies.resolve(anomalyId, 2L, "Selected valid copy", 150L)
                .toCompletableFuture().join());
        assertFalse(anomalies.resolve(anomalyId, 2L, "Stale resolution", 160L)
                .toCompletableFuture().join());
    }

    private static void createReplacementAnomalies(
            SQLiteAnomalyRepository anomalies, TrackingIdentity identity) {
        anomalies.create(openAnomaly(
                        UUID.randomUUID(),
                        identity.instance().id(),
                        identity.definitionId(),
                        InstanceAnomaly.Type.DUPLICATE_INSTANCE,
                        "Conflict reappeared",
                        200L))
                .toCompletableFuture().join();
        anomalies.create(openAnomaly(
                        UUID.randomUUID(),
                        identity.instance().id(),
                        identity.definitionId(),
                        InstanceAnomaly.Type.MALFORMED_STACK,
                        "Stack size exceeds one",
                        210L))
                .toCompletableFuture().join();
    }

    private static void assertAnomalyPages(
            SQLiteAnomalyRepository anomalies, LoreInstance instance) {
        Page<InstanceAnomaly> active = anomalies.listActive(PageRequest.first(1))
                .toCompletableFuture().join();
        assertEquals(1, active.items().size());
        assertTrue(active.hasMore());
        Page<InstanceAnomaly> first = anomalies
                .listByInstance(instance.id(), PageRequest.first(2))
                .toCompletableFuture().join();
        Page<InstanceAnomaly> second = anomalies
                .listByInstance(instance.id(), new PageRequest(2, 2))
                .toCompletableFuture().join();
        assertEquals(2, first.items().size());
        assertTrue(first.hasMore());
        assertEquals(1, second.items().size());
        assertFalse(second.hasMore());
    }

    private static void assertResolvedAnomalyAfterRestart(Path database, UUID anomalyId) {
        SQLiteStorageRuntime runtime = start(database);
        try {
            InstanceAnomaly restored = new SQLiteAnomalyRepository(runtime)
                    .findById(anomalyId).toCompletableFuture().join().orElseThrow();
            assertEquals(InstanceAnomaly.Status.RESOLVED, restored.status());
            assertEquals("staff:operator", restored.acknowledgedBy());
            assertEquals("Selected valid copy", restored.resolutionDetail());
            assertEquals(3L, restored.stateRevision());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static InstanceAnomaly openAnomaly(
            UUID anomalyId,
            LoreInstanceId instanceId,
            LoreDefinitionId definitionId,
            InstanceAnomaly.Type type,
            String detail,
            long seenAt) {
        return new InstanceAnomaly(
                anomalyId,
                instanceId,
                definitionId,
                type,
                InstanceAnomaly.Status.OPEN,
                detail,
                seenAt,
                seenAt,
                null,
                null,
                null,
                null,
                0L);
    }

    private static LoreInstance instance(LoreDefinitionId definitionId) {
        return new LoreInstance(
                new LoreInstanceId(UUID.randomUUID()),
                definitionId,
                new TemplateRevision(1),
                new TemplateRevision(1),
                LoreInstanceLifecycle.ACTIVE,
                10L,
                null);
    }

    private static void seed(
            SQLiteStorageRuntime runtime,
            LoreDefinitionId definitionId,
            LoreInstance instance) {
        new SQLiteDefinitionRepository(runtime)
                .create(
                        new LoreDefinition(
                                definitionId,
                                new DefinitionKey("tracking-test"),
                                "Tracking Test",
                                new TemplateRevision(1),
                                1L,
                                null),
                        new LoreDefinitionRevision(
                                definitionId,
                                new TemplateRevision(1),
                                1,
                                new byte[] {1},
                                1L))
                .toCompletableFuture().join();
        new SQLiteInstanceRepository(runtime).create(instance).toCompletableFuture().join();
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

    private record TrackingIdentity(
            LoreDefinitionId definitionId, LoreInstance instance) {
    }

    private record ObservationScenario(
            LocationDescriptor playerLocation,
            InstanceObservation first,
            InstanceObservation conflicting) {
    }
}
