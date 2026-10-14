package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
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

class SQLiteLegacyLocationMigrationTest {
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
    void historicalWorldNameCannotSilentlyBecomeUuidChestAfterNaturalReload() {
        var legacy = new LocationDescriptor(LocationDescriptor.Type.BLOCK_CONTAINER,
                "minecraft:overworld:-16:64:15", "slot:26");
        var versioned = new LocationDescriptor(LocationDescriptor.Type.BLOCK_CONTAINER,
                "block-v2:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa:-16:64:15", "slot:26");
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("historical-key.db"));
        try {
            seedActiveInstance(runtime);
            var store = new SQLiteTrackingObservationStore(runtime);
            assertEquals(TrackingObservationUseCase.Status.RECORDED,
                    record(store, present(legacy, reconciliation()), 1_000L).status());
            assertEquals(TrackingObservationUseCase.Status.RECORDED,
                    record(store, lastConfirmed(legacy), 1_050L).status());

            assertEquals(TrackingObservationUseCase.Status.CONFLICT_RECORDED,
                    record(store, present(versioned, reconciliation()), 1_100L).status());
            assertEquals(InstanceCurrentState.State.CONFLICTING, currentState(runtime).state());
            var observations = new SQLiteObservationRepository(runtime)
                    .listByInstance(INSTANCE_ID, PageRequest.first(20))
                    .toCompletableFuture().join().items();
            assertTrue(observations.stream().anyMatch(o ->
                    o.location().equals(legacy)
                            && o.confidence() == InstanceObservation.Confidence.LAST_CONFIRMED));
            assertTrue(observations.stream().anyMatch(o ->
                    o.location().equals(versioned)
                            && o.confidence() == InstanceObservation.Confidence.CONFLICTING));
            // A legacy alias is not evidence of two genuine copies.
            assertTrue(new SQLiteAnomalyRepository(runtime)
                    .listByInstance(INSTANCE_ID, PageRequest.first(10))
                    .toCompletableFuture().join().items().isEmpty());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void oldWorldNameMustNotChooseEitherUuidOrDoubleChestAutomatically() {
        var legacy = new LocationDescriptor(LocationDescriptor.Type.BLOCK_CONTAINER,
                "minecraft:overworld:15:64:-16", "slot:0");
        var candidates = java.util.List.of(
                "block-v2:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa:15:64:-16",
                "block-v2:bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb:15:64:-16",
                "double-chest:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa:15:64:-16:16:64:-16");
        for (int index = 0; index < candidates.size(); index++) {
            SQLiteStorageRuntime runtime = start(
                    temporaryDirectory.resolve("unknown-world-" + index + ".db"));
            try {
                seedActiveInstance(runtime);
                var store = new SQLiteTrackingObservationStore(runtime);
                record(store, present(legacy, reconciliation()), 1_000L);
                var candidate = new LocationDescriptor(LocationDescriptor.Type.BLOCK_CONTAINER,
                        candidates.get(index), "slot:0");
                assertEquals(TrackingObservationUseCase.Status.CONFLICT_RECORDED,
                        record(store, present(candidate, authoritative()), 1_100L).status());
                assertEquals(InstanceCurrentState.State.CONFLICTING, currentState(runtime).state());
            } finally {
                runtime.close(Duration.ofSeconds(5));
            }
        }
    }

    @Test
    void failedLegacyFenceAuditRollsBackAllAmbiguityEvidence() {
        var legacy = new LocationDescriptor(LocationDescriptor.Type.BLOCK_CONTAINER,
                "minecraft:overworld:0:64:0", "slot:5");
        var current = new LocationDescriptor(LocationDescriptor.Type.BLOCK_CONTAINER,
                "block-v2:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa:0:64:0", "slot:5");
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("migration-rollback.db"));
        try {
            seedActiveInstance(runtime);
            var store = new SQLiteTrackingObservationStore(runtime);
            record(store, present(legacy, reconciliation()), 1_000L);
            runtime.execute(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE TRIGGER reject_legacy_fence "
                            + "BEFORE INSERT ON audit_events "
                            + "WHEN NEW.event_type = 'tracking_legacy_world_key_review' "
                            + "BEGIN SELECT RAISE(ABORT, 'forced migration rollback'); END");
                }
                return null;
            }).toCompletableFuture().join();

            assertThrows(CompletionException.class, () ->
                    record(store, present(current, reconciliation()), 1_100L));
            assertEquals(InstanceCurrentState.State.CONFIRMED_NOW, currentState(runtime).state());
            assertEquals(legacy, currentState(runtime).location());
            assertEquals(1, new SQLiteObservationRepository(runtime)
                    .listByInstance(INSTANCE_ID, PageRequest.first(20))
                    .toCompletableFuture().join().items().size());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void dryRunCountsLegacyRowsWithoutRewritingStateOrGuessingWorldUuid() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("migration-audit.db"));
        try {
            seedActiveInstance(runtime);
            var store = new SQLiteTrackingObservationStore(runtime);
            var legacy = new LocationDescriptor(LocationDescriptor.Type.BLOCK_CONTAINER,
                    "minecraft:overworld:16:64:-1", "slot:5");
            record(store, present(legacy, reconciliation()), 1_000L);
            record(store, lastConfirmed(legacy), 1_050L);
            UUID first = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
            UUID second = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

            var ambiguous = runtime.execute(connection -> SQLiteLegacyKeyDryRun.inspect(
                    connection, java.util.Map.of("minecraft:overworld",
                            java.util.List.of(first, second)))).toCompletableFuture().join();
            assertEquals(1L, ambiguous.current().legacyAmbiguousName());
            assertEquals(2L, ambiguous.history().legacyAmbiguousName());
            assertEquals(1L, ambiguous.current().lastConfirmedLegacy());

            var unknown = runtime.execute(connection -> SQLiteLegacyKeyDryRun.inspect(
                    connection, java.util.Map.<String, java.util.List<UUID>>of()))
                    .toCompletableFuture().join();
            assertEquals(1L, unknown.current().legacyUnknownName());

            var apparentSingle = runtime.execute(connection -> SQLiteLegacyKeyDryRun.inspect(
                    connection, java.util.Map.of("minecraft:overworld",
                            java.util.List.of(first)))).toCompletableFuture().join();
            assertEquals(1L, apparentSingle.current().legacyKnownName());
            assertEquals(InstanceCurrentState.State.LAST_CONFIRMED, currentState(runtime).state());
            assertEquals(legacy, currentState(runtime).location());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void realLockedWalWriteCannotAdvanceOrForgeHistory() throws Exception {
        Path database = temporaryDirectory.resolve("locked-wal.db");
        MetricsPort metrics = MetricsPort.noOp();
        SQLiteStorageRuntime runtime = new SQLiteStorageRuntime(
                new SQLiteConnectionFactory(database, 100),
                new MigrationRunner(),
                new BoundedDatabaseExecutor("wal-lock-test", 32, metrics), metrics);
        try {
            runtime.start().toCompletableFuture().join();
            seedActiveInstance(runtime);
            var store = new SQLiteTrackingObservationStore(runtime);
            record(store, present(SLOT_ONE, reconciliation()), 1_000L);
            try (var lock = java.sql.DriverManager.getConnection(
                    "jdbc:sqlite:" + database.toAbsolutePath());
                    var statement = lock.createStatement()) {
                statement.execute("PRAGMA journal_mode = WAL");
                statement.execute("BEGIN IMMEDIATE");
                CompletionException failure = assertThrows(CompletionException.class, () ->
                        record(store, present(SLOT_TWO, authoritative()), 1_100L));
                assertInstanceOf(SQLException.class, failure.getCause());
                statement.execute("ROLLBACK");
            }
            assertEquals(SLOT_ONE, currentState(runtime).location());
            assertEquals(1, new SQLiteObservationRepository(runtime)
                    .listByInstance(INSTANCE_ID, PageRequest.first(20))
                    .toCompletableFuture().join().items().size());
            assertEquals(TrackingObservationUseCase.Status.RECORDED,
                    record(store, present(SLOT_TWO, authoritative()), 1_200L).status());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void crossChunkPairedSlotMoveRollsBackEntireLockedWalWriteAndReopensCleanly()
            throws Exception {
        Path database = temporaryDirectory.resolve("paired-cross-chunk-locked-wal.db");
        var source = new LocationDescriptor(LocationDescriptor.Type.BLOCK_CONTAINER,
                "double-chest:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa:"
                        + "15:64:8|16:64:8:left=1", "slot:0");
        var moved = new LocationDescriptor(LocationDescriptor.Type.BLOCK_CONTAINER,
                source.locationKey(), "slot:53");
        MetricsPort metrics = MetricsPort.noOp();
        SQLiteStorageRuntime runtime = new SQLiteStorageRuntime(
                new SQLiteConnectionFactory(database, 100),
                new MigrationRunner(),
                new BoundedDatabaseExecutor("paired-wal-rollback-test", 32, metrics), metrics);
        try {
            runtime.start().toCompletableFuture().join();
            seedActiveInstance(runtime);
            var store = new SQLiteTrackingObservationStore(runtime);
            assertEquals(TrackingObservationUseCase.Status.RECORDED,
                    record(store, present(source, reconciliation()), 1_000L).status());
            try (var lock = java.sql.DriverManager.getConnection(
                    "jdbc:sqlite:" + database.toAbsolutePath());
                    var statement = lock.createStatement()) {
                statement.execute("PRAGMA journal_mode = WAL");
                statement.execute("BEGIN IMMEDIATE");
                var failure = assertThrows(CompletionException.class, () ->
                        record(store, present(moved, authoritative()), 1_100L));
                assertInstanceOf(SQLException.class, failure.getCause());
                statement.execute("ROLLBACK");
            }
            assertEquals(source, currentState(runtime).location());
            assertEquals(1, observedCount(runtime));
            assertEquals(TrackingObservationUseCase.Status.RECORDED,
                    record(store, present(moved, authoritative()), 1_200L).status());
            assertEquals(InstanceCurrentState.State.CONFIRMED_NOW,
                    currentState(runtime).state());
            assertEquals(moved, currentState(runtime).location());
            assertEquals(2, observedCount(runtime));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
        SQLiteStorageRuntime reopened = start(database);
        try {
            assertEquals(moved, currentState(reopened).location());
            assertEquals(2, observedCount(reopened));
            reopened.execute(connection -> {
                try (var statement = connection.createStatement()) {
                    assertEquals("ok", statement.executeQuery(
                            "PRAGMA integrity_check").getString(1));
                }
                return null;
            }).toCompletableFuture().join();
        } finally {
            reopened.close(Duration.ofSeconds(5));
        }
    }

    private static int observedCount(SQLiteStorageRuntime runtime) {
        return new SQLiteObservationRepository(runtime)
                .listByInstance(INSTANCE_ID, PageRequest.first(20))
                .toCompletableFuture().join().items().size();
    }

    @Test
    void nestedBlockRootKeyMigrationCannotPromoteUnknownWorldIdentity() {
        var oldNested = new LocationDescriptor(LocationDescriptor.Type.NESTED_CONTAINER,
                "root:BLOCK_CONTAINER:minecraft:overworld:-1:64:16",
                "slot:7/shulker:5/bundle:1");
        var uuidNested = new LocationDescriptor(LocationDescriptor.Type.NESTED_CONTAINER,
                "root:BLOCK_CONTAINER:block-v2:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa:-1:64:16",
                "slot:7/shulker:5/bundle:1");
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("nested-legacy.db"));
        try {
            seedActiveInstance(runtime);
            var store = new SQLiteTrackingObservationStore(runtime);
            record(store, present(oldNested, reconciliation()), 1_000L);
            record(store, lastConfirmed(oldNested), 1_050L);
            assertEquals(TrackingObservationUseCase.Status.CONFLICT_RECORDED,
                    record(store, present(uuidNested, reconciliation()), 1_100L).status());
            assertEquals(InstanceCurrentState.State.CONFLICTING, currentState(runtime).state());
            var report = runtime.execute(connection -> SQLiteLegacyKeyDryRun.inspect(
                    connection, java.util.Map.of("minecraft:overworld",
                            java.util.List.of(UUID.fromString(
                                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")))))
                    .toCompletableFuture().join();
            assertEquals(3L, report.history().legacyKnownName());
            assertTrue(new SQLiteObservationRepository(runtime)
                    .listByInstance(INSTANCE_ID, PageRequest.first(20))
                    .toCompletableFuture().join().items().stream()
                    .anyMatch(o -> oldNested.equals(o.location())));
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
