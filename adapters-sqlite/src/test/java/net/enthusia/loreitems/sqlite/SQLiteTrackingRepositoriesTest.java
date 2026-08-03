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
        LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
        LoreInstance instance = instance(definitionId);

        SQLiteStorageRuntime firstRuntime = start(database);
        InstanceObservation firstObservation;
        InstanceObservation conflictingObservation;
        try {
            seed(firstRuntime, definitionId, instance);
            SQLiteObservationRepository observations =
                    new SQLiteObservationRepository(firstRuntime);
            SQLiteCurrentStateRepository currentStates =
                    new SQLiteCurrentStateRepository(firstRuntime);

            LocationDescriptor playerLocation = new LocationDescriptor(
                    LocationDescriptor.Type.PLAYER_INVENTORY,
                    "player:" + UUID.randomUUID() + ":slot:4",
                    null);
            firstObservation = observations.append(new InstanceObservation(
                            0L,
                            instance.id(),
                            definitionId,
                            playerLocation,
                            InstanceObservation.Confidence.CONFIRMED_NOW,
                            "player-inventory-close",
                            100L))
                    .toCompletableFuture()
                    .join();
            conflictingObservation = observations.append(new InstanceObservation(
                            0L,
                            instance.id(),
                            definitionId,
                            new LocationDescriptor(
                                    LocationDescriptor.Type.BLOCK_CONTAINER,
                                    "world:0,64,0",
                                    "slot:2/shulker:slot:5"),
                            InstanceObservation.Confidence.CONFLICTING,
                            "chunk-load",
                            200L))
                    .toCompletableFuture()
                    .join();

            Page<InstanceObservation> firstPage = observations
                    .listByInstance(instance.id(), PageRequest.first(1))
                    .toCompletableFuture()
                    .join();
            Page<InstanceObservation> secondPage = observations
                    .listByInstance(instance.id(), new PageRequest(1, 1))
                    .toCompletableFuture()
                    .join();
            assertEquals(1, firstPage.items().size());
            assertTrue(firstPage.hasMore());
            assertEquals(conflictingObservation.observationId(),
                    firstPage.items().getFirst().observationId());
            assertEquals(1, secondPage.items().size());
            assertFalse(secondPage.hasMore());

            Page<InstanceObservation> byLocation = observations
                    .listByLocation(
                            LocationDescriptor.Type.PLAYER_INVENTORY,
                            playerLocation.locationKey(),
                            PageRequest.first(2))
                    .toCompletableFuture()
                    .join();
            assertEquals(1, byLocation.items().size());

            currentStates.create(new InstanceCurrentState(
                            instance.id(),
                            InstanceCurrentState.State.CONFIRMED_NOW,
                            playerLocation,
                            firstObservation.observationId(),
                            0L,
                            100L))
                    .toCompletableFuture()
                    .join();
            assertThrows(
                    CompletionException.class,
                    () -> currentStates.create(new InstanceCurrentState(
                                    instance.id(),
                                    InstanceCurrentState.State.CONFIRMED_NOW,
                                    playerLocation,
                                    firstObservation.observationId(),
                                    0L,
                                    100L))
                            .toCompletableFuture()
                            .join());

            InstanceCurrentState conflictingState = new InstanceCurrentState(
                    instance.id(),
                    InstanceCurrentState.State.CONFLICTING,
                    conflictingObservation.location(),
                    conflictingObservation.observationId(),
                    1L,
                    201L);
            assertTrue(currentStates
                    .compareAndSet(instance.id(), 0L, conflictingState)
                    .toCompletableFuture()
                    .join());
            assertFalse(currentStates
                    .compareAndSet(instance.id(), 0L, conflictingState)
                    .toCompletableFuture()
                    .join());

            LoreDefinitionId wrongDefinition = new LoreDefinitionId(UUID.randomUUID());
            assertThrows(
                    CompletionException.class,
                    () -> observations.append(new InstanceObservation(
                                    0L,
                                    instance.id(),
                                    wrongDefinition,
                                    playerLocation,
                                    InstanceObservation.Confidence.CONFIRMED_NOW,
                                    "invalid-definition",
                                    300L))
                            .toCompletableFuture()
                            .join());
        } finally {
            firstRuntime.close(Duration.ofSeconds(5));
        }

        SQLiteStorageRuntime secondRuntime = start(database);
        try {
            InstanceCurrentState restored = new SQLiteCurrentStateRepository(secondRuntime)
                    .findByInstance(instance.id())
                    .toCompletableFuture()
                    .join()
                    .orElseThrow();
            assertEquals(InstanceCurrentState.State.CONFLICTING, restored.state());
            assertEquals(conflictingObservation.observationId(), restored.lastObservationId());
            assertEquals(1L, restored.stateRevision());

            InstanceObservation restoredObservation =
                    new SQLiteObservationRepository(secondRuntime)
                            .findById(firstObservation.observationId())
                            .toCompletableFuture()
                            .join()
                            .orElseThrow();
            assertEquals(firstObservation, restoredObservation);
        } finally {
            secondRuntime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void enforcesActiveAnomalyUniquenessAndLifecycleWithBoundedHistory() {
        Path database = temporaryDirectory.resolve("anomalies.db");
        LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
        LoreInstance instance = instance(definitionId);
        UUID firstAnomalyId = UUID.randomUUID();

        SQLiteStorageRuntime firstRuntime = start(database);
        try {
            seed(firstRuntime, definitionId, instance);
            SQLiteAnomalyRepository anomalies = new SQLiteAnomalyRepository(firstRuntime);
            InstanceAnomaly first = openAnomaly(
                    firstAnomalyId,
                    instance.id(),
                    definitionId,
                    InstanceAnomaly.Type.DUPLICATE_INSTANCE,
                    "Two credible locations",
                    100L);
            anomalies.create(first).toCompletableFuture().join();

            assertThrows(
                    CompletionException.class,
                    () -> anomalies.create(openAnomaly(
                                    UUID.randomUUID(),
                                    instance.id(),
                                    definitionId,
                                    InstanceAnomaly.Type.DUPLICATE_INSTANCE,
                                    "Same active identity",
                                    110L))
                            .toCompletableFuture()
                            .join());

            assertTrue(anomalies
                    .refresh(firstAnomalyId, 0L, "Three credible locations", 120L)
                    .toCompletableFuture()
                    .join());
            assertFalse(anomalies
                    .refresh(firstAnomalyId, 0L, "Stale refresh", 130L)
                    .toCompletableFuture()
                    .join());
            assertTrue(anomalies
                    .acknowledge(firstAnomalyId, 1L, "staff:operator", 140L)
                    .toCompletableFuture()
                    .join());
            assertTrue(anomalies
                    .resolve(firstAnomalyId, 2L, "Selected valid copy", 150L)
                    .toCompletableFuture()
                    .join());
            assertFalse(anomalies
                    .resolve(firstAnomalyId, 2L, "Stale resolution", 160L)
                    .toCompletableFuture()
                    .join());

            InstanceAnomaly replacement = openAnomaly(
                    UUID.randomUUID(),
                    instance.id(),
                    definitionId,
                    InstanceAnomaly.Type.DUPLICATE_INSTANCE,
                    "Conflict reappeared",
                    200L);
            anomalies.create(replacement).toCompletableFuture().join();
            anomalies.create(openAnomaly(
                            UUID.randomUUID(),
                            instance.id(),
                            definitionId,
                            InstanceAnomaly.Type.MALFORMED_STACK,
                            "Stack size exceeds one",
                            210L))
                    .toCompletableFuture()
                    .join();

            Page<InstanceAnomaly> active = anomalies
                    .listActive(PageRequest.first(1))
                    .toCompletableFuture()
                    .join();
            assertEquals(1, active.items().size());
            assertTrue(active.hasMore());

            Page<InstanceAnomaly> historyFirst = anomalies
                    .listByInstance(instance.id(), PageRequest.first(2))
                    .toCompletableFuture()
                    .join();
            Page<InstanceAnomaly> historySecond = anomalies
                    .listByInstance(instance.id(), new PageRequest(2, 2))
                    .toCompletableFuture()
                    .join();
            assertEquals(2, historyFirst.items().size());
            assertTrue(historyFirst.hasMore());
            assertEquals(1, historySecond.items().size());
            assertFalse(historySecond.hasMore());
        } finally {
            firstRuntime.close(Duration.ofSeconds(5));
        }

        SQLiteStorageRuntime secondRuntime = start(database);
        try {
            InstanceAnomaly restored = new SQLiteAnomalyRepository(secondRuntime)
                    .findById(firstAnomalyId)
                    .toCompletableFuture()
                    .join()
                    .orElseThrow();
            assertEquals(InstanceAnomaly.Status.RESOLVED, restored.status());
            assertEquals("staff:operator", restored.acknowledgedBy());
            assertEquals("Selected valid copy", restored.resolutionDetail());
            assertEquals(3L, restored.stateRevision());
        } finally {
            secondRuntime.close(Duration.ofSeconds(5));
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
                .toCompletableFuture()
                .join();
        new SQLiteInstanceRepository(runtime)
                .create(instance)
                .toCompletableFuture()
                .join();
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
