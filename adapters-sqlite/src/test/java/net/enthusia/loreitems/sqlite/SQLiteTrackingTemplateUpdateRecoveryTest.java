package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.InstanceAnomaly;
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

class SQLiteTrackingTemplateUpdateRecoveryTest {
    private static final LoreDefinitionId DEFINITION_ID = new LoreDefinitionId(
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
    private static final LoreInstanceId INSTANCE_ID = new LoreInstanceId(
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
    private static final TemplateRevision REVISION_ONE = new TemplateRevision(1);
    private static final TemplateRevision REVISION_TWO = new TemplateRevision(2);
    private static final LocationDescriptor PLAYER_SLOT = new LocationDescriptor(
            LocationDescriptor.Type.PLAYER_INVENTORY,
            "player:cccccccc-cccc-cccc-cccc-cccccccccccc",
            "slot:4");

    @TempDir
    Path temporaryDirectory;

    @Test
    void desiredRevisionRemainsTrackableAcrossRecoverableTemplateUpdateStates() {
        assertRecoverableState("CLAIMED", "claimed.db");
        assertRecoverableState("REVIEW_REQUIRED", "review-required.db");
        assertRecoverableState("PENDING", "pending.db");
    }

    @Test
    void desiredRevisionWithoutRecoverableTemplateUpdateStillFencesMismatch() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("terminal.db"));
        try {
            seedDesiredRevision(runtime, "COMPLETED");
            SQLiteTrackingObservationStore store = new SQLiteTrackingObservationStore(runtime);

            TrackingObservationUseCase.Result result = recordRevisionTwo(store);

            assertEquals(TrackingObservationUseCase.Status.IDENTITY_MISMATCH, result.status());
            assertEquals(InstanceCurrentState.State.CONFLICTING, currentState(runtime).state());
            var anomalies = new SQLiteAnomalyRepository(runtime)
                    .listByInstance(INSTANCE_ID, PageRequest.first(10))
                    .toCompletableFuture().join().items();
            assertEquals(1, anomalies.size());
            assertEquals(InstanceAnomaly.Type.IDENTITY_MISMATCH, anomalies.getFirst().type());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private void assertRecoverableState(String mutationState, String databaseName) {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve(databaseName));
        try {
            seedDesiredRevision(runtime, mutationState);
            SQLiteTrackingObservationStore store = new SQLiteTrackingObservationStore(runtime);

            TrackingObservationUseCase.Result result = recordRevisionTwo(store);

            assertEquals(
                    TrackingObservationUseCase.Status.RECORDED,
                    result.status(),
                    mutationState);
            InstanceCurrentState current = currentState(runtime);
            assertEquals(InstanceCurrentState.State.CONFIRMED_NOW, current.state());
            assertEquals(PLAYER_SLOT, current.location());
            assertTrue(new SQLiteAnomalyRepository(runtime)
                    .listByInstance(INSTANCE_ID, PageRequest.first(10))
                    .toCompletableFuture().join().items().isEmpty());
            String durableState = runtime.execute(connection -> {
                        try (PreparedStatement statement = connection.prepareStatement(
                                "SELECT state FROM pending_mutations WHERE instance_id = ?")) {
                            statement.setString(1, INSTANCE_ID.value().toString());
                            try (var rows = statement.executeQuery()) {
                                return rows.next() ? rows.getString(1) : null;
                            }
                        }
                    })
                    .toCompletableFuture().join();
            assertEquals(mutationState, durableState);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static TrackingObservationUseCase.Result recordRevisionTwo(
            SQLiteTrackingObservationStore store) {
        TrackingObservationUseCase.Request request = new TrackingObservationUseCase.Request(
                new LoreItemIdentity(DEFINITION_ID, INSTANCE_ID, REVISION_TWO),
                PLAYER_SLOT,
                TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "template-update-recovery-test");
        return store.record(request, Instant.ofEpochMilli(1_000L))
                .toCompletableFuture().join();
    }

    private static void seedDesiredRevision(
            SQLiteStorageRuntime runtime,
            String mutationState) {
        seedDefinitionAndInstance(runtime);
        seedDesiredRevisionMutation(runtime, mutationState);
    }

    private static void seedDefinitionAndInstance(SQLiteStorageRuntime runtime) {
        new SQLiteDefinitionRepository(runtime).create(
                        new LoreDefinition(
                                DEFINITION_ID,
                                new DefinitionKey("tracking_recovery"),
                                "Tracking Recovery",
                                REVISION_ONE,
                                500L,
                                null),
                        new LoreDefinitionRevision(
                                DEFINITION_ID,
                                REVISION_ONE,
                                1,
                                new byte[] {1, 2, 3},
                                500L))
                .toCompletableFuture().join();
        new SQLiteInstanceRepository(runtime).create(new LoreInstance(
                        INSTANCE_ID,
                        DEFINITION_ID,
                        REVISION_ONE,
                        REVISION_ONE,
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

    private static void seedDesiredRevisionMutation(
            SQLiteStorageRuntime runtime,
            String mutationState) {
        runtime.execute(connection -> SQLiteTransactions.inTransaction(connection, transaction -> {
                    insertRevisionAndAdvanceDefinition(transaction);
                    updateDesiredRevision(transaction);
                    insertTemplateUpdateMutation(transaction, mutationState);
                    return null;
                }))
                .toCompletableFuture().join();
    }

    private static void insertRevisionAndAdvanceDefinition(java.sql.Connection transaction)
            throws java.sql.SQLException {
        try (PreparedStatement revision = transaction.prepareStatement(
                "INSERT INTO lore_definition_revisions"
                        + "(definition_id, revision, codec_version, template_blob, created_at) "
                        + "VALUES (?, 2, 1, ?, 600)")) {
            revision.setString(1, DEFINITION_ID.value().toString());
            revision.setBytes(2, new byte[] {4, 5, 6});
            revision.executeUpdate();
        }
        try (PreparedStatement definition = transaction.prepareStatement(
                "UPDATE lore_definitions SET current_revision = 2 WHERE definition_id = ?")) {
            definition.setString(1, DEFINITION_ID.value().toString());
            definition.executeUpdate();
        }
    }

    private static void updateDesiredRevision(java.sql.Connection transaction)
            throws java.sql.SQLException {
        try (PreparedStatement instance = transaction.prepareStatement(
                "UPDATE lore_instances SET desired_revision = 2 WHERE instance_id = ?")) {
            instance.setString(1, INSTANCE_ID.value().toString());
            instance.executeUpdate();
        }
    }

    private static void insertTemplateUpdateMutation(
            java.sql.Connection transaction,
            String mutationState) throws java.sql.SQLException {
        try (PreparedStatement mutation = transaction.prepareStatement(
                "INSERT INTO pending_mutations(mutation_id, mutation_type, definition_id, "
                        + "instance_id, desired_revision, state, claim_token, "
                        + "claim_expires_at, attempt_count, next_attempt_at, created_at, "
                        + "updated_at) VALUES (?, 'TEMPLATE_UPDATE', ?, ?, 2, ?, ?, ?, 1, "
                        + "600, 600, 600)")) {
            mutation.setString(1, UUID.randomUUID().toString());
            mutation.setString(2, DEFINITION_ID.value().toString());
            mutation.setString(3, INSTANCE_ID.value().toString());
            mutation.setString(4, mutationState);
            if ("CLAIMED".equals(mutationState)) {
                mutation.setString(5, "recovery-claim");
                mutation.setLong(6, 5_000L);
            } else {
                mutation.setNull(5, java.sql.Types.VARCHAR);
                mutation.setNull(6, java.sql.Types.BIGINT);
            }
            mutation.executeUpdate();
        }
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
                new BoundedDatabaseExecutor("tracking-recovery-test", 32, metrics),
                metrics);
        assertEquals(
                net.enthusia.loreitems.application.StorageState.READ_WRITE,
                runtime.start().toCompletableFuture().join().state());
        return runtime;
    }
}
