package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ControlRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.Preview;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.PreviewRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ReviewRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ReviewResolution;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ReviewStatus;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.StartRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.StartStatus;
import net.enthusia.loreitems.application.DestructiveOperationStore;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase.Observation;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase.Status;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.PersistingDestructiveAdministrationUseCase;
import net.enthusia.loreitems.application.PersistingDestructiveRemovalExecutionUseCase;
import net.enthusia.loreitems.application.StorageState;
import net.enthusia.loreitems.domain.DestructiveEffectState;
import net.enthusia.loreitems.domain.DestructiveOperationState;
import net.enthusia.loreitems.domain.DestructiveOperationType;
import net.enthusia.loreitems.domain.DestructiveTargetState;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteDestructiveOperationStoreTest {
    private static final Instant NOW = Instant.ofEpochMilli(2_000L);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String LOCATION_TYPE = "PLAYER_INVENTORY";
    private static final String LOCATION_KEY = "player:one";
    private static final String FINGERPRINT = "fingerprint-before";

    @TempDir
    Path temporaryDirectory;

    @Test
    void fullDeleteCommitsMarkerSnapshotAndIdempotentAcceptance() {
        SQLiteStorageRuntime runtime = start("delete.db");
        try {
            Seed seed = seed(runtime, true);
            DestructiveAdministrationUseCase administration = administration(runtime);
            Preview preview = preview(
                    administration,
                    DestructiveOperationType.DELETE_DEFINITION,
                    seed,
                    null);

            var started = administration.start(new StartRequest(
                            preview, "admin", "delete-request-1"))
                    .toCompletableFuture().join();
            var repeated = administration.start(new StartRequest(
                            preview, "admin", "delete-request-1"))
                    .toCompletableFuture().join();

            assertEquals(StartStatus.STARTED, started.status());
            assertEquals(StartStatus.ALREADY_ACCEPTED, repeated.status());
            assertEquals(started.operation().operationId(), repeated.operation().operationId());
            assertEquals(1L, started.operation().targetCount());
            assertTrue(definitionDeleted(runtime, seed.definitionId()));
            assertEquals(1L, count(runtime, "deleted_definition_markers"));
            assertEquals(1L, count(runtime, "destructive_targets"));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void pauseFencesClaimsAndVerifiedRemovalCompletesTheParent() {
        SQLiteStorageRuntime runtime = start("pause.db");
        try {
            Seed seed = seed(runtime, true);
            DestructiveAdministrationUseCase administration = administration(runtime);
            Preview preview = preview(
                    administration,
                    DestructiveOperationType.PURGE_DEFINITION,
                    seed,
                    null);
            var started = administration.start(new StartRequest(
                            preview, "admin", "purge-request-1"))
                    .toCompletableFuture().join();
            UUID operationId = started.operation().operationId();

            administration.pause(new ControlRequest(operationId, "admin"))
                    .toCompletableFuture().join();
            DestructiveRemovalExecutionUseCase execution = execution(runtime, CLOCK, 30L);
            assertEquals(Status.NO_PENDING_WORK, execution.prepare(observation(seed, LOCATION_KEY))
                    .toCompletableFuture().join().status());

            administration.resume(new ControlRequest(operationId, "admin"))
                    .toCompletableFuture().join();
            var prepared = execution.prepare(observation(seed, LOCATION_KEY))
                    .toCompletableFuture().join();
            assertEquals(Status.PREPARED, prepared.status());
            assertTrue(execution.complete(prepared.preparedRemoval(), FINGERPRINT)
                    .toCompletableFuture().join());

            var operation = administration.listOperations(PageRequest.first(10))
                    .toCompletableFuture().join().items().getFirst();
            assertEquals(DestructiveOperationState.COMPLETED, operation.state());
            assertEquals(1L, operation.completedCount());
            assertEquals("REMOVED", instanceLifecycle(runtime, seed.instanceId()));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void exactRemovalMovementRequiresEvidenceReview() {
        SQLiteStorageRuntime runtime = start("exact-review.db");
        try {
            Seed seed = seed(runtime, true);
            DestructiveAdministrationUseCase administration = administration(runtime);
            Preview preview = preview(
                    administration,
                    DestructiveOperationType.EXACT_INSTANCE_REMOVAL,
                    seed,
                    seed.instanceId());
            var started = administration.start(new StartRequest(
                            preview, "admin", "exact-request-1"))
                    .toCompletableFuture().join();

            DestructiveRemovalExecutionUseCase execution = execution(runtime, CLOCK, 30L);
            var prepare = execution.prepare(observation(seed, "player:two"))
                    .toCompletableFuture().join();
            assertEquals(Status.REVIEW_REQUIRED, prepare.status());

            var target = administration.listTargets(
                            started.operation().operationId(), PageRequest.first(10))
                    .toCompletableFuture().join().items().getFirst();
            assertEquals(DestructiveTargetState.REVIEW_REQUIRED, target.state());
            assertEquals(DestructiveEffectState.NONE_OBSERVED, target.effectState());

            var invalid = administration.resolveReview(new ReviewRequest(
                            started.operation().operationId(),
                            seed.instanceId(),
                            ReviewResolution.MARK_VERIFIED_REMOVED,
                            "admin",
                            "The item moved; absence was not verified."))
                    .toCompletableFuture().join();
            assertEquals(ReviewStatus.EVIDENCE_MISMATCH, invalid.status());

            var requeued = administration.resolveReview(new ReviewRequest(
                            started.operation().operationId(),
                            seed.instanceId(),
                            ReviewResolution.REQUEUE_NO_SIDE_EFFECT,
                            "admin",
                            "The original slot still contains the untouched target."))
                    .toCompletableFuture().join();
            assertEquals(ReviewStatus.RESOLVED, requeued.status());
            assertEquals(DestructiveTargetState.PENDING, requeued.target().state());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void expiredClaimBecomesAmbiguousAndCannotBlindlyRetry() {
        SQLiteStorageRuntime runtime = start("expired.db");
        try {
            Seed seed = seed(runtime, true);
            DestructiveAdministrationUseCase administration = administration(runtime);
            Preview preview = preview(
                    administration,
                    DestructiveOperationType.PURGE_DEFINITION,
                    seed,
                    null);
            var started = administration.start(new StartRequest(
                            preview, "admin", "purge-expired"))
                    .toCompletableFuture().join();
            DestructiveRemovalExecutionUseCase execution = execution(runtime, CLOCK, 1L);
            assertEquals(Status.PREPARED, execution.prepare(observation(seed, LOCATION_KEY))
                    .toCompletableFuture().join().status());

            DestructiveOperationStore store = new SQLiteDestructiveOperationStore(runtime);
            assertEquals(1, store.moveExpiredClaimsToReview(
                            Instant.ofEpochMilli(3_001L), 10)
                    .toCompletableFuture().join());
            var target = administration.listTargets(
                            started.operation().operationId(), PageRequest.first(10))
                    .toCompletableFuture().join().items().getFirst();
            assertEquals(DestructiveEffectState.AMBIGUOUS, target.effectState());

            var retry = administration.resolveReview(new ReviewRequest(
                            started.operation().operationId(),
                            seed.instanceId(),
                            ReviewResolution.REQUEUE_NO_SIDE_EFFECT,
                            "admin",
                            "No physical inspection was performed."))
                    .toCompletableFuture().join();
            assertEquals(ReviewStatus.EVIDENCE_MISMATCH, retry.status());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void lateCopyAfterEmptyFullDeleteCreatesTargetAndReopensOperation() {
        SQLiteStorageRuntime runtime = start("late-delete.db");
        try {
            Seed seed = seed(runtime, false);
            DestructiveAdministrationUseCase administration = administration(runtime);
            Preview preview = preview(
                    administration,
                    DestructiveOperationType.DELETE_DEFINITION,
                    seed,
                    null);
            var started = administration.start(new StartRequest(
                            preview, "admin", "empty-delete"))
                    .toCompletableFuture().join();
            assertEquals(DestructiveOperationState.COMPLETED, started.operation().state());
            assertEquals(0L, started.operation().targetCount());

            DestructiveRemovalExecutionUseCase execution = execution(runtime, CLOCK, 30L);
            var prepared = execution.prepare(observation(seed, LOCATION_KEY))
                    .toCompletableFuture().join();
            assertEquals(Status.PREPARED, prepared.status());
            assertTrue(execution.complete(prepared.preparedRemoval(), FINGERPRINT)
                    .toCompletableFuture().join());

            var operation = administration.listOperations(PageRequest.first(10))
                    .toCompletableFuture().join().items().getFirst();
            assertEquals(1L, operation.targetCount());
            assertEquals(1L, operation.completedCount());
            assertEquals(DestructiveOperationState.COMPLETED, operation.state());
            assertEquals("REMOVED", instanceLifecycle(runtime, seed.instanceId()));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private SQLiteStorageRuntime start(String fileName) {
        MetricsPort metrics = MetricsPort.noOp();
        SQLiteStorageRuntime runtime = new SQLiteStorageRuntime(
                new SQLiteConnectionFactory(temporaryDirectory.resolve(fileName), 5_000),
                new MigrationRunner(),
                new BoundedDatabaseExecutor("destructive-test", 64, metrics),
                metrics);
        assertEquals(
                StorageState.READ_WRITE,
                runtime.start().toCompletableFuture().join().state());
        return runtime;
    }

    private static DestructiveAdministrationUseCase administration(
            SQLiteStorageRuntime runtime) {
        return new PersistingDestructiveAdministrationUseCase(
                new SQLiteDestructiveOperationStore(runtime), CLOCK);
    }

    private static DestructiveRemovalExecutionUseCase execution(
            SQLiteStorageRuntime runtime,
            Clock clock,
            long leaseSeconds) {
        return new PersistingDestructiveRemovalExecutionUseCase(
                new SQLiteDestructiveOperationStore(runtime),
                clock,
                Duration.ofSeconds(leaseSeconds));
    }

    private static Preview preview(
            DestructiveAdministrationUseCase administration,
            DestructiveOperationType type,
            Seed seed,
            LoreInstanceId exactInstanceId) {
        Preview preview = administration.preview(new PreviewRequest(
                        type, seed.definitionId(), exactInstanceId))
                .toCompletableFuture().join().orElseThrow();
        assertFalse(preview.confirmationToken().isBlank());
        return preview;
    }

    private static Observation observation(Seed seed, String locationKey) {
        return new Observation(
                new LoreItemIdentity(
                        seed.definitionId(),
                        seed.instanceId(),
                        new TemplateRevision(1L)),
                LOCATION_TYPE,
                locationKey,
                null,
                FINGERPRINT);
    }

    private static Seed seed(SQLiteStorageRuntime runtime, boolean withInstance) {
        LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
        LoreInstanceId instanceId = new LoreInstanceId(UUID.randomUUID());
        runtime.execute(connection -> {
            insertDefinition(connection, definitionId);
            if (withInstance) {
                insertInstance(connection, definitionId, instanceId);
                insertCurrentState(connection, definitionId, instanceId);
            }
            return null;
        }).toCompletableFuture().join();
        return new Seed(definitionId, instanceId);
    }

    private static void insertDefinition(
            Connection connection,
            LoreDefinitionId definitionId) throws SQLException {
        try (PreparedStatement definition = connection.prepareStatement(
                "INSERT INTO lore_definitions(definition_id, lookup_key, display_name, "
                        + "current_revision, created_at, deleted_at) "
                        + "VALUES (?, 'destructive_test', 'Destructive Test', 1, 1000, NULL)")) {
            definition.setString(1, definitionId.value().toString());
            definition.executeUpdate();
        }
        try (PreparedStatement revision = connection.prepareStatement(
                "INSERT INTO lore_definition_revisions(definition_id, revision, codec_version, "
                        + "template_blob, created_at) VALUES (?, 1, 1, ?, 1000)")) {
            revision.setString(1, definitionId.value().toString());
            revision.setBytes(2, new byte[] {1});
            revision.executeUpdate();
        }
    }

    private static void insertInstance(
            Connection connection,
            LoreDefinitionId definitionId,
            LoreInstanceId instanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO lore_instances(instance_id, definition_id, applied_revision, "
                        + "desired_revision, lifecycle_state, created_at, terminal_at) "
                        + "VALUES (?, ?, 1, 1, 'ACTIVE', 1000, NULL)")) {
            statement.setString(1, instanceId.value().toString());
            statement.setString(2, definitionId.value().toString());
            statement.executeUpdate();
        }
    }

    private static void insertCurrentState(
            Connection connection,
            LoreDefinitionId definitionId,
            LoreInstanceId instanceId) throws SQLException {
        long observationId;
        try (PreparedStatement observation = connection.prepareStatement(
                "INSERT INTO instance_observations(instance_id, definition_id, location_type, "
                        + "location_key, container_path, confidence, source, observed_at) "
                        + "VALUES (?, ?, ?, ?, NULL, 'CONFIRMED_NOW', 'test', 1000)",
                Statement.RETURN_GENERATED_KEYS)) {
            observation.setString(1, instanceId.value().toString());
            observation.setString(2, definitionId.value().toString());
            observation.setString(3, LOCATION_TYPE);
            observation.setString(4, LOCATION_KEY);
            observation.executeUpdate();
            try (ResultSet keys = observation.getGeneratedKeys()) {
                assertTrue(keys.next());
                observationId = keys.getLong(1);
            }
        }
        try (PreparedStatement current = connection.prepareStatement(
                "INSERT INTO instance_current_state(instance_id, state, location_type, "
                        + "location_key, container_path, last_observation_id, state_revision, "
                        + "updated_at) VALUES (?, 'CONFIRMED_NOW', ?, ?, NULL, ?, 1, 1000)")) {
            current.setString(1, instanceId.value().toString());
            current.setString(2, LOCATION_TYPE);
            current.setString(3, LOCATION_KEY);
            current.setLong(4, observationId);
            current.executeUpdate();
        }
    }

    private static boolean definitionDeleted(
            SQLiteStorageRuntime runtime,
            LoreDefinitionId definitionId) {
        return runtime.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT deleted_at FROM lore_definitions WHERE definition_id = ?")) {
                statement.setString(1, definitionId.value().toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    resultSet.getLong(1);
                    return !resultSet.wasNull();
                }
            }
        }).toCompletableFuture().join();
    }

    private static long count(SQLiteStorageRuntime runtime, String table) {
        return runtime.execute(connection -> {
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                assertTrue(resultSet.next());
                return resultSet.getLong(1);
            }
        }).toCompletableFuture().join();
    }

    private static String instanceLifecycle(
            SQLiteStorageRuntime runtime,
            LoreInstanceId instanceId) {
        return runtime.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT lifecycle_state FROM lore_instances WHERE instance_id = ?")) {
                statement.setString(1, instanceId.value().toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    String value = resultSet.getString(1);
                    assertNotNull(value);
                    return value;
                }
            }
        }).toCompletableFuture().join();
    }

    private record Seed(
            LoreDefinitionId definitionId,
            LoreInstanceId instanceId) {}
}