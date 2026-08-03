package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.ItemAnomalyObservationStore;
import net.enthusia.loreitems.application.ItemAnomalyObservationUseCase;
import net.enthusia.loreitems.application.LoreItemIdentity;
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

class SQLiteItemAnomalyObservationStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void recordsRefreshesAndAuditsMalformedEvidenceWhileFencingCurrentState() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("malformed.db"));
        try {
            TestIdentity identity = seedActiveInstance(runtime);
            SQLiteItemAnomalyObservationStore store =
                    new SQLiteItemAnomalyObservationStore(runtime);
            LocationDescriptor malformedLocation = new LocationDescriptor(
                    LocationDescriptor.Type.PLAYER_INVENTORY,
                    "player:" + UUID.randomUUID(),
                    "slot:7");
            ItemAnomalyObservationUseCase.Request firstRequest = request(
                    identity,
                    malformedLocation,
                    "STACKING_VIOLATION: tracked stack amount was two");

            ItemAnomalyObservationUseCase.Result first = store.record(
                            new ItemAnomalyObservationStore.Observation(
                                    UUID.randomUUID(), firstRequest, 200L))
                    .toCompletableFuture().join();

            assertEquals(ItemAnomalyObservationUseCase.Status.RECORDED, first.status());
            assertFencedState(runtime, identity.instance().id(), malformedLocation, 1L);
            assertEquals(1, warningAnomalies(runtime).items().size());
            assertEquals(2, observations(runtime, identity.instance().id()).items().size());
            assertEquals(1, audit(runtime, identity.instance().id()).items().size());

            ItemAnomalyObservationUseCase.Request refreshedRequest = request(
                    identity,
                    malformedLocation,
                    "STACKING_VIOLATION: malformed stack was observed again");
            ItemAnomalyObservationUseCase.Result refreshed = store.record(
                            new ItemAnomalyObservationStore.Observation(
                                    UUID.randomUUID(), refreshedRequest, 300L))
                    .toCompletableFuture().join();

            assertEquals(ItemAnomalyObservationUseCase.Status.REFRESHED, refreshed.status());
            assertFencedState(runtime, identity.instance().id(), malformedLocation, 2L);
            Page<InstanceAnomaly> warnings = warningAnomalies(runtime);
            assertEquals(1, warnings.items().size());
            assertEquals(1L, warnings.items().getFirst().stateRevision());
            assertEquals(3, observations(runtime, identity.instance().id()).items().size());
            assertEquals(2, audit(runtime, identity.instance().id()).items().size());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void rejectsMismatchedIdentityWithoutChangingEvidence() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("mismatch.db"));
        try {
            TestIdentity identity = seedActiveInstance(runtime);
            LoreItemIdentity mismatched = new LoreItemIdentity(
                    new LoreDefinitionId(UUID.randomUUID()),
                    identity.loreIdentity().instanceId(),
                    identity.loreIdentity().appliedRevision());
            ItemAnomalyObservationUseCase.Request request =
                    new ItemAnomalyObservationUseCase.Request(
                            ItemAnomalyObservationUseCase.Kind.MALFORMED_STACK,
                            mismatched,
                            new LocationDescriptor(
                                    LocationDescriptor.Type.PLAYER_INVENTORY,
                                    "player:" + UUID.randomUUID(),
                                    "slot:1"),
                            "test-mismatch",
                            "Observed identity disagreed with durable definition");

            ItemAnomalyObservationUseCase.Result result =
                    new SQLiteItemAnomalyObservationStore(runtime)
                            .record(new ItemAnomalyObservationStore.Observation(
                                    UUID.randomUUID(), request, 200L))
                            .toCompletableFuture().join();

            assertEquals(
                    ItemAnomalyObservationUseCase.Status.IDENTITY_MISMATCH,
                    result.status());
            InstanceCurrentState current = new SQLiteCurrentStateRepository(runtime)
                    .findByInstance(identity.instance().id())
                    .toCompletableFuture().join().orElseThrow();
            assertEquals(InstanceCurrentState.State.CONFIRMED_NOW, current.state());
            assertEquals(0L, current.stateRevision());
            assertTrue(warningAnomalies(runtime).items().isEmpty());
            assertEquals(1, observations(runtime, identity.instance().id()).items().size());
            assertTrue(audit(runtime, identity.instance().id()).items().isEmpty());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static ItemAnomalyObservationUseCase.Request request(
            TestIdentity identity,
            LocationDescriptor location,
            String detail) {
        return new ItemAnomalyObservationUseCase.Request(
                ItemAnomalyObservationUseCase.Kind.MALFORMED_STACK,
                identity.loreIdentity(),
                location,
                "test-malformed-observation",
                detail);
    }

    private static TestIdentity seedActiveInstance(SQLiteStorageRuntime runtime) {
        LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
        LoreInstance instance = new LoreInstance(
                new LoreInstanceId(UUID.randomUUID()),
                definitionId,
                new TemplateRevision(1),
                new TemplateRevision(1),
                LoreInstanceLifecycle.ACTIVE,
                10L,
                null);
        new SQLiteDefinitionRepository(runtime)
                .create(
                        new LoreDefinition(
                                definitionId,
                                new DefinitionKey("anomaly-test"),
                                "Anomaly Test",
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
        LocationDescriptor initialLocation = new LocationDescriptor(
                LocationDescriptor.Type.PLAYER_INVENTORY,
                "player:" + UUID.randomUUID(),
                "slot:0");
        InstanceObservation initialObservation = new SQLiteObservationRepository(runtime)
                .append(new InstanceObservation(
                        0L,
                        instance.id(),
                        definitionId,
                        initialLocation,
                        InstanceObservation.Confidence.CONFIRMED_NOW,
                        "test-seed",
                        100L))
                .toCompletableFuture().join();
        new SQLiteCurrentStateRepository(runtime)
                .create(new InstanceCurrentState(
                        instance.id(),
                        InstanceCurrentState.State.CONFIRMED_NOW,
                        initialLocation,
                        initialObservation.observationId(),
                        0L,
                        100L))
                .toCompletableFuture().join();
        return new TestIdentity(
                instance,
                new LoreItemIdentity(
                        definitionId,
                        instance.id(),
                        instance.appliedRevision()));
    }

    private static void assertFencedState(
            SQLiteStorageRuntime runtime,
            LoreInstanceId instanceId,
            LocationDescriptor expectedLocation,
            long expectedRevision) {
        InstanceCurrentState current = new SQLiteCurrentStateRepository(runtime)
                .findByInstance(instanceId)
                .toCompletableFuture().join().orElseThrow();
        assertEquals(InstanceCurrentState.State.CONFLICTING, current.state());
        assertEquals(expectedLocation, current.location());
        assertEquals(expectedRevision, current.stateRevision());
    }

    private static Page<InstanceAnomaly> warningAnomalies(SQLiteStorageRuntime runtime) {
        Page<InstanceAnomaly> page = new SQLiteAnomalyRepository(runtime)
                .listActiveWarnings(PageRequest.first(10))
                .toCompletableFuture().join();
        assertFalse(page.hasMore());
        return page;
    }

    private static Page<InstanceObservation> observations(
            SQLiteStorageRuntime runtime,
            LoreInstanceId instanceId) {
        return new SQLiteObservationRepository(runtime)
                .listByInstance(instanceId, PageRequest.first(10))
                .toCompletableFuture().join();
    }

    private static Page<AuditEventRecord> audit(
            SQLiteStorageRuntime runtime,
            LoreInstanceId instanceId) {
        return new SQLiteAuditRepository(runtime)
                .listByAggregate(
                        "lore_instance",
                        instanceId.value().toString(),
                        PageRequest.first(10))
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

    private record TestIdentity(
            LoreInstance instance,
            LoreItemIdentity loreIdentity) {}
}
