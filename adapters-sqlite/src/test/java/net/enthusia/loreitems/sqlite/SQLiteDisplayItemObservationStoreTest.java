package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.enthusia.loreitems.application.DisplayItemObservationUseCase;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
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

class SQLiteDisplayItemObservationStoreTest {
    private static final LoreDefinitionId DEFINITION_ID = new LoreDefinitionId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final LoreInstanceId INSTANCE_ID = new LoreInstanceId(
            UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private static final TemplateRevision REVISION = new TemplateRevision(1);
    private static final LoreItemIdentity IDENTITY =
            new LoreItemIdentity(DEFINITION_ID, INSTANCE_ID, REVISION);
    private static final LocationDescriptor FRAME_LOCATION = new LocationDescriptor(
            LocationDescriptor.Type.ITEM_FRAME,
            "minecraft:overworld:2:64:3:33333333-3333-3333-3333-333333333333",
            "item");

    @TempDir
    Path temporaryDirectory;

    @Test
    void confirmedAndRemovedDisplayEvidenceCommitsAtomicallyAcrossRestart() {
        Path database = temporaryDirectory.resolve("display.db");
        SQLiteStorageRuntime runtime = start(database);
        try {
            seedActiveInstance(runtime);
            SQLiteDisplayItemObservationStore store =
                    new SQLiteDisplayItemObservationStore(runtime);

            assertEquals(
                    DisplayItemObservationUseCase.Status.RECORDED,
                    record(store, present(FRAME_LOCATION), 1_000L).status());
            assertEquals(
                    DisplayItemObservationUseCase.Status.UNCHANGED,
                    record(store, present(FRAME_LOCATION), 1_100L).status());
            assertEquals(
                    DisplayItemObservationUseCase.Status.RECORDED,
                    record(store, absent(FRAME_LOCATION), 1_200L).status());

            assertDisplayState(runtime);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }

        runtime = start(database);
        try {
            assertDisplayState(runtime);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void olderDisplayEvidenceCannotRegressNewerCurrentState() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("stale-time.db"));
        try {
            seedActiveInstance(runtime);
            SQLiteDisplayItemObservationStore store =
                    new SQLiteDisplayItemObservationStore(runtime);
            LocationDescriptor staleFrame = new LocationDescriptor(
                    LocationDescriptor.Type.ITEM_FRAME,
                    "minecraft:overworld:9:70:9:44444444-4444-4444-4444-444444444444",
                    "item");

            assertEquals(
                    DisplayItemObservationUseCase.Status.RECORDED,
                    record(store, present(FRAME_LOCATION), 2_000L).status());
            assertEquals(
                    DisplayItemObservationUseCase.Status.STALE,
                    record(store, present(staleFrame), 1_500L).status());

            InstanceCurrentState current = currentState(runtime);
            assertEquals(InstanceCurrentState.State.CONFIRMED_NOW, current.state());
            assertEquals(FRAME_LOCATION, current.location());
            assertEquals(1L, current.stateRevision());
            assertEquals(2_000L, current.updatedAtEpochMillis());
            assertEquals(1, new SQLiteObservationRepository(runtime)
                    .listByInstance(INSTANCE_ID, PageRequest.first(10))
                    .toCompletableFuture().join().items().size());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void unknownMismatchAnomalyAndStaleRemovalDoNotReplaceCurrentState() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("blocked.db"));
        try {
            SQLiteDisplayItemObservationStore store =
                    new SQLiteDisplayItemObservationStore(runtime);
            assertUnknownAndMismatchedIdentity(runtime, store);
            LocationDescriptor anotherFrame = assertStaleRemovalPreservesLocation(runtime, store);
            createBlockingDuplicateAnomaly(runtime);
            assertEquals(
                    DisplayItemObservationUseCase.Status.BLOCKED_ANOMALY,
                    record(store, present(anotherFrame), 1_060L).status());
            assertEquals(FRAME_LOCATION, currentState(runtime).location());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void failedAuditRollsBackObservationAndCurrentState() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("rollback.db"));
        try {
            seedActiveInstance(runtime);
            runtime.execute(connection -> {
                        try (var statement = connection.createStatement()) {
                            statement.execute("CREATE TRIGGER fail_display_audit "
                                    + "BEFORE INSERT ON audit_events "
                                    + "WHEN NEW.event_type = 'display_item_confirmed' "
                                    + "BEGIN SELECT RAISE(ABORT, 'forced audit failure'); END");
                        }
                        return null;
                    })
                    .toCompletableFuture().join();
            SQLiteDisplayItemObservationStore store =
                    new SQLiteDisplayItemObservationStore(runtime);

            assertThrows(CompletionException.class, () -> record(
                    store, present(FRAME_LOCATION), 1_000L));

            assertEquals(
                    InstanceCurrentState.State.MISSING_UNRESOLVED,
                    currentState(runtime).state());
            assertTrue(new SQLiteObservationRepository(runtime)
                    .listByInstance(INSTANCE_ID, PageRequest.first(10))
                    .toCompletableFuture().join().items().isEmpty());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static void assertUnknownAndMismatchedIdentity(
            SQLiteStorageRuntime runtime,
            SQLiteDisplayItemObservationStore store) {
        assertEquals(
                DisplayItemObservationUseCase.Status.UNKNOWN_INSTANCE,
                record(store, present(FRAME_LOCATION), 1_000L).status());
        seedActiveInstance(runtime);
        DisplayItemObservationUseCase.Request mismatch =
                new DisplayItemObservationUseCase.Request(
                        new LoreItemIdentity(
                                new LoreDefinitionId(UUID.fromString(
                                        "99999999-9999-9999-9999-999999999999")),
                                INSTANCE_ID,
                                REVISION),
                        FRAME_LOCATION,
                        DisplayItemObservationUseCase.Presence.PRESENT,
                        "item-frame-change");
        assertEquals(
                DisplayItemObservationUseCase.Status.IDENTITY_MISMATCH,
                record(store, mismatch, 1_010L).status());
        assertEquals(
                DisplayItemObservationUseCase.Status.STALE,
                record(store, absent(FRAME_LOCATION), 1_020L).status());
    }

    private static LocationDescriptor assertStaleRemovalPreservesLocation(
            SQLiteStorageRuntime runtime,
            SQLiteDisplayItemObservationStore store) {
        assertEquals(
                DisplayItemObservationUseCase.Status.RECORDED,
                record(store, present(FRAME_LOCATION), 1_030L).status());
        LocationDescriptor anotherFrame = new LocationDescriptor(
                LocationDescriptor.Type.ITEM_FRAME,
                "minecraft:overworld:9:70:9:44444444-4444-4444-4444-444444444444",
                "item");
        assertEquals(
                DisplayItemObservationUseCase.Status.STALE,
                record(store, absent(anotherFrame), 1_040L).status());
        assertEquals(FRAME_LOCATION, currentState(runtime).location());
        return anotherFrame;
    }

    private static void createBlockingDuplicateAnomaly(SQLiteStorageRuntime runtime) {
        new SQLiteAnomalyRepository(runtime).create(new InstanceAnomaly(
                        UUID.fromString("55555555-5555-5555-5555-555555555555"),
                        INSTANCE_ID,
                        DEFINITION_ID,
                        InstanceAnomaly.Type.DUPLICATE_INSTANCE,
                        InstanceAnomaly.Status.OPEN,
                        "Two display copies are credible.",
                        1_050L,
                        1_050L,
                        null,
                        null,
                        null,
                        null,
                        0L))
                .toCompletableFuture().join();
    }

    private static void assertDisplayState(SQLiteStorageRuntime runtime) {
        var observations = new SQLiteObservationRepository(runtime)
                .listByInstance(INSTANCE_ID, PageRequest.first(10))
                .toCompletableFuture().join().items();
        assertEquals(2, observations.size());
        assertEquals(
                InstanceObservation.Confidence.LAST_CONFIRMED,
                observations.getFirst().confidence());
        assertEquals(
                InstanceObservation.Confidence.CONFIRMED_NOW,
                observations.get(1).confidence());

        InstanceCurrentState current = currentState(runtime);
        assertEquals(InstanceCurrentState.State.LAST_CONFIRMED, current.state());
        assertEquals(FRAME_LOCATION, current.location());
        assertEquals(observations.getFirst().observationId(), current.lastObservationId());
        assertEquals(2L, current.stateRevision());

        var audits = new SQLiteAuditRepository(runtime)
                .listByAggregate(
                        "lore_instance",
                        INSTANCE_ID.value().toString(),
                        PageRequest.first(10))
                .toCompletableFuture().join().items();
        assertEquals(2, audits.size());
        assertEquals("display_item_last_confirmed", audits.getFirst().eventType());
        assertEquals("display_item_confirmed", audits.get(1).eventType());
    }

    private static DisplayItemObservationUseCase.Result record(
            SQLiteDisplayItemObservationStore store,
            DisplayItemObservationUseCase.Request request,
            long observedAt) {
        return store.record(request, Instant.ofEpochMilli(observedAt))
                .toCompletableFuture().join();
    }

    private static DisplayItemObservationUseCase.Request present(
            LocationDescriptor location) {
        return request(location, DisplayItemObservationUseCase.Presence.PRESENT);
    }

    private static DisplayItemObservationUseCase.Request absent(
            LocationDescriptor location) {
        return request(location, DisplayItemObservationUseCase.Presence.ABSENT);
    }

    private static DisplayItemObservationUseCase.Request request(
            LocationDescriptor location,
            DisplayItemObservationUseCase.Presence presence) {
        return new DisplayItemObservationUseCase.Request(
                IDENTITY,
                location,
                presence,
                "item-frame-change");
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
