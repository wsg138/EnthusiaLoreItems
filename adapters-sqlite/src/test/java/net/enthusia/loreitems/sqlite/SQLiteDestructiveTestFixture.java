package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.Preview;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.PreviewRequest;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase.Observation;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.PersistingDestructiveAdministrationUseCase;
import net.enthusia.loreitems.application.PersistingDestructiveRemovalExecutionUseCase;
import net.enthusia.loreitems.domain.DestructiveOperationType;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;

final class SQLiteDestructiveTestFixture implements AutoCloseable {
    static final String LOCATION_TYPE = "PLAYER_INVENTORY";
    static final String LOCATION_KEY = "player:one";
    static final String FINGERPRINT = "fingerprint-before";

    private final SQLiteStorageRuntime runtime;
    private final Clock clock;

    SQLiteDestructiveTestFixture(Path directory, String fileName, Clock clock) {
        this.clock = clock;
        MetricsPort metrics = MetricsPort.noOp();
        runtime = new SQLiteStorageRuntime(
                new SQLiteConnectionFactory(directory.resolve(fileName), 5_000),
                new MigrationRunner(),
                new BoundedDatabaseExecutor("destructive-test", 64, metrics),
                metrics);
        runtime.start().toCompletableFuture().join();
    }

    DestructiveAdministrationUseCase administration() {
        return new PersistingDestructiveAdministrationUseCase(
                new SQLiteDestructiveOperationStore(runtime), clock);
    }

    DestructiveRemovalExecutionUseCase execution(long leaseSeconds) {
        return new PersistingDestructiveRemovalExecutionUseCase(
                new SQLiteDestructiveOperationStore(runtime),
                clock,
                Duration.ofSeconds(leaseSeconds));
    }

    net.enthusia.loreitems.application.DestructiveOperationStore store() {
        return new SQLiteDestructiveOperationStore(runtime);
    }

    Preview preview(
            DestructiveOperationType type,
            Seed seed,
            LoreInstanceId exactInstanceId) {
        return administration().preview(new PreviewRequest(
                        type, seed.definitionId(), exactInstanceId))
                .toCompletableFuture().join().orElseThrow();
    }

    Observation observation(Seed seed, String locationKey) {
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

    Seed seed(boolean withInstance) {
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

    void addOpenAnomaly(Seed seed, String anomalyType) {
        runtime.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO instance_anomalies(anomaly_id, instance_id, definition_id, "
                            + "anomaly_type, status, detail, first_seen_at, last_seen_at, "
                            + "acknowledged_at, acknowledged_by, resolved_at, resolution_detail, "
                            + "state_revision) VALUES (?, ?, ?, ?, 'OPEN', 'destructive test anomaly', "
                            + "1000, 1000, NULL, NULL, NULL, NULL, 0)")) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, seed.instanceId().value().toString());
                statement.setString(3, seed.definitionId().value().toString());
                statement.setString(4, anomalyType);
                statement.executeUpdate();
            }
            return null;
        }).toCompletableFuture().join();
    }

    boolean definitionDeleted(LoreDefinitionId definitionId) {
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

    long deletedMarkerCount() {
        return runtime.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM deleted_definition_markers");
                    ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getLong(1);
            }
        }).toCompletableFuture().join();
    }

    long destructiveTargetCount() {
        return runtime.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM destructive_targets");
                    ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getLong(1);
            }
        }).toCompletableFuture().join();
    }

    String instanceLifecycle(LoreInstanceId instanceId) {
        return runtime.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT lifecycle_state FROM lore_instances WHERE instance_id = ?")) {
                statement.setString(1, instanceId.value().toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    return resultSet.getString(1);
                }
            }
        }).toCompletableFuture().join();
    }

    void moveCurrentState(LoreInstanceId instanceId, String locationKey) {
        runtime.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE instance_current_state SET location_key = ?, "
                            + "state_revision = state_revision + 1, updated_at = 2000 "
                            + "WHERE instance_id = ?")) {
                statement.setString(1, locationKey);
                statement.setString(2, instanceId.value().toString());
                assertTrue(statement.executeUpdate() == 1);
            }
            return null;
        }).toCompletableFuture().join();
    }

    @Override
    public void close() {
        runtime.close(Duration.ofSeconds(5));
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
        long observationId = insertObservation(connection, definitionId, instanceId);
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

    private static long insertObservation(
            Connection connection,
            LoreDefinitionId definitionId,
            LoreInstanceId instanceId) throws SQLException {
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
                return keys.getLong(1);
            }
        }
    }

    record Seed(LoreDefinitionId definitionId, LoreInstanceId instanceId) {}
}
