package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.enthusia.loreitems.application.DirectDeliveryRecord;
import net.enthusia.loreitems.application.ExternalDeliveryAcceptance;
import net.enthusia.loreitems.application.ExternalDeliveryCommand;
import net.enthusia.loreitems.application.ExternalDeliveryOutcome;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.PreparedDirectDelivery;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.DirectDeliveryState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteDirectDeliveryRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void duplicateExternalOperationCreatesOnlyOneInstanceAndDelivery() throws Exception {
        Path database = temporaryDirectory.resolve("idempotency.db");
        SQLiteStorageRuntime runtime = start(database);
        try {
            seedDefinition(runtime, "millionaire");
            SQLiteDirectDeliveryRepository repository = new SQLiteDirectDeliveryRepository(runtime);
            ExternalDeliveryCommand command = new ExternalDeliveryCommand(
                    new DefinitionKey("millionaire"), UUID.randomUUID(), "tags:reward:42");

            ExternalDeliveryAcceptance first = repository
                    .acceptExternal(command, Instant.ofEpochMilli(1_000L))
                    .toCompletableFuture()
                    .join();
            ExternalDeliveryAcceptance second = repository
                    .acceptExternal(command, Instant.ofEpochMilli(2_000L))
                    .toCompletableFuture()
                    .join();

            assertEquals(ExternalDeliveryOutcome.ACCEPTED_QUEUED, first.outcome());
            assertEquals(ExternalDeliveryOutcome.ALREADY_ACCEPTED, second.outcome());
            assertEquals(first.deliveryId(), second.deliveryId());
            assertEquals(1, count(runtime, CountTable.LORE_INSTANCES));
            assertEquals(1, count(runtime, CountTable.DIRECT_DELIVERIES));
            assertEquals(1, count(runtime, CountTable.EXTERNAL_REQUESTS));
            assertEquals(1, count(runtime, CountTable.OBSERVATIONS));
            assertEquals(1, count(runtime, CountTable.CURRENT_STATES));
            assertEquals(1, count(runtime, CountTable.AUDIT_EVENTS));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void externalAcceptanceRollsBackAllRowsWhenFinalInsertFails() throws Exception {
        Path database = temporaryDirectory.resolve("rollback.db");
        SQLiteStorageRuntime runtime = start(database);
        try {
            seedDefinition(runtime, "rollback-safe");
            runtime.execute(connection -> {
                        try (var statement = connection.createStatement()) {
                            statement.execute("CREATE TRIGGER fail_external_request "
                                    + "BEFORE INSERT ON external_delivery_requests "
                                    + "BEGIN SELECT RAISE(ABORT, 'forced failure'); END");
                        }
                        return null;
                    })
                    .toCompletableFuture()
                    .join();
            SQLiteDirectDeliveryRepository repository = new SQLiteDirectDeliveryRepository(runtime);
            ExternalDeliveryCommand command = new ExternalDeliveryCommand(
                    new DefinitionKey("rollback-safe"), UUID.randomUUID(), "rollback-1");

            assertThrows(CompletionException.class, () -> repository
                    .acceptExternal(command, Instant.ofEpochMilli(1_000L))
                    .toCompletableFuture()
                    .join());

            assertEquals(0, count(runtime, CountTable.LORE_INSTANCES));
            assertEquals(0, count(runtime, CountTable.DIRECT_DELIVERIES));
            assertEquals(0, count(runtime, CountTable.EXTERNAL_REQUESTS));
            assertEquals(0, count(runtime, CountTable.OBSERVATIONS));
            assertEquals(0, count(runtime, CountTable.CURRENT_STATES));
            assertEquals(0, count(runtime, CountTable.AUDIT_EVENTS));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void claimTokenAndLiveLeaseFenceStateTransitions() throws Exception {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("claim.db"));
        try {
            SQLiteDirectDeliveryRepository repository =
                    new SQLiteDirectDeliveryRepository(runtime);
            seedClaimableDelivery(runtime, repository);
            UUID deliveryId = claimOnce(repository);
            assertClaimFencing(repository, deliveryId);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void preparedClaimCompletesQueuedLocationAsVerifiedInventoryObservation() throws Exception {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("complete.db"));
        try {
            seedDefinition(runtime, "deliverable");
            SQLiteDirectDeliveryRepository repository = new SQLiteDirectDeliveryRepository(runtime);
            UUID playerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
            ExternalDeliveryAcceptance accepted = repository.acceptExternal(
                            new ExternalDeliveryCommand(
                                    new DefinitionKey("deliverable"), playerId, "complete-1"),
                            Instant.ofEpochMilli(1_000L))
                    .toCompletableFuture().join();

            Page<PreparedDirectDelivery> page = repository.claimPreparedPending(
                            "worker-complete",
                            Instant.ofEpochMilli(2_000L),
                            Duration.ofSeconds(30),
                            10)
                    .toCompletableFuture().join();

            assertEquals(1, page.items().size());
            PreparedDirectDelivery delivery = page.items().getFirst();
            assertEquals(accepted.deliveryId().orElseThrow(), delivery.deliveryId());
            assertEquals(playerId, delivery.playerId());
            assertEquals(1, delivery.template().codecVersion());
            assertEquals(1, delivery.template().payload()[0]);
            assertTrue(repository.completeClaimed(
                            delivery,
                            7,
                            "a".repeat(64),
                            Instant.ofEpochMilli(3_000L))
                    .toCompletableFuture().join());

            assertEquals(0, repository.listNonTerminal(PageRequest.first(10))
                    .toCompletableFuture().join().items().size());
            assertEquals(2, count(runtime, CountTable.OBSERVATIONS));
            assertEquals(1, count(runtime, CountTable.CURRENT_STATES));
            assertEquals(2, count(runtime, CountTable.AUDIT_EVENTS));
            assertEquals("PLAYER_INVENTORY", currentLocationType(runtime, delivery.instanceId().value()));
            assertEquals("storage:7", currentContainerPath(runtime, delivery.instanceId().value()));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void offlineDeferralWaitsUntilDueOrExplicitPlayerWake() throws Exception {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("defer.db"));
        try {
            seedDefinition(runtime, "offline");
            SQLiteDirectDeliveryRepository repository = new SQLiteDirectDeliveryRepository(runtime);
            UUID playerId = UUID.fromString("22222222-2222-2222-2222-222222222222");
            repository.acceptExternal(
                            new ExternalDeliveryCommand(
                                    new DefinitionKey("offline"), playerId, "offline-1"),
                            Instant.ofEpochMilli(1_000L))
                    .toCompletableFuture().join();
            PreparedDirectDelivery delivery = repository.claimPreparedPending(
                            "worker-offline",
                            Instant.ofEpochMilli(2_000L),
                            Duration.ofSeconds(30),
                            10)
                    .toCompletableFuture().join().items().getFirst();

            assertTrue(repository.deferClaimed(
                            delivery.deliveryId(),
                            delivery.claimToken(),
                            Instant.ofEpochMilli(2_100L),
                            Instant.ofEpochMilli(30_000L))
                    .toCompletableFuture().join());
            assertTrue(repository.claimPreparedPending(
                            "too-early",
                            Instant.ofEpochMilli(3_000L),
                            Duration.ofSeconds(30),
                            10)
                    .toCompletableFuture().join().items().isEmpty());
            assertEquals(1, repository.wakePendingForPlayer(
                            playerId,
                            Instant.ofEpochMilli(3_100L),
                            10)
                    .toCompletableFuture().join());
            assertEquals(1, repository.claimPreparedPending(
                            "after-wake",
                            Instant.ofEpochMilli(3_101L),
                            Duration.ofSeconds(30),
                            10)
                    .toCompletableFuture().join().items().size());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void expiredClaimBecomesReviewRequiredAfterRestart() throws Exception {
        Path database = temporaryDirectory.resolve("restart.db");
        seedExpiredDeliveries(database);
        assertBoundedDeliveryRecovery(database);
    }

    private static void seedClaimableDelivery(
            SQLiteStorageRuntime runtime, SQLiteDirectDeliveryRepository repository) {
        seedDefinition(runtime, "claimable");
        repository.acceptExternal(
                        new ExternalDeliveryCommand(
                                new DefinitionKey("claimable"), UUID.randomUUID(), "claim-1"),
                        Instant.ofEpochMilli(1_000L))
                .toCompletableFuture().join();
    }

    private static UUID claimOnce(SQLiteDirectDeliveryRepository repository) {
        Page<DirectDeliveryRecord> claimed = repository.claimPending(
                        "worker-a", Instant.ofEpochMilli(2_000L), Duration.ofSeconds(30), 10)
                .toCompletableFuture().join();
        Page<DirectDeliveryRecord> duplicateClaim = repository.claimPending(
                        "worker-b", Instant.ofEpochMilli(2_001L), Duration.ofSeconds(30), 10)
                .toCompletableFuture().join();
        assertEquals(1, claimed.items().size());
        assertTrue(duplicateClaim.items().isEmpty());
        return claimed.items().getFirst().deliveryId();
    }

    private static void assertClaimFencing(
            SQLiteDirectDeliveryRepository repository, UUID deliveryId) {
        assertFalse(transition(repository, deliveryId, "worker-b", 3_000L));
        assertFalse(transition(repository, deliveryId, "worker-a", 32_001L));
        assertTrue(transition(repository, deliveryId, "worker-a", 3_000L));
    }

    private static boolean transition(
            SQLiteDirectDeliveryRepository repository, UUID deliveryId, String worker, long now) {
        return repository.transitionClaimed(
                        deliveryId, DirectDeliveryState.RESERVED, DirectDeliveryState.APPLIED,
                        worker, Instant.ofEpochMilli(now))
                .toCompletableFuture().join();
    }

    private static void seedExpiredDeliveries(Path database) {
        SQLiteStorageRuntime runtime = start(database);
        seedDefinition(runtime, "restart-safe");
        SQLiteDirectDeliveryRepository repository = new SQLiteDirectDeliveryRepository(runtime);
        accept(repository, "restart-1", 1_000L);
        accept(repository, "restart-2", 1_001L);
        repository.claimPending(
                        "worker-before-restart", Instant.ofEpochMilli(2_000L),
                        Duration.ofMillis(10L), 10)
                .toCompletableFuture().join();
        runtime.close(Duration.ofSeconds(5));
    }

    private static void accept(
            SQLiteDirectDeliveryRepository repository, String operationId, long now) {
        repository.acceptExternal(
                        new ExternalDeliveryCommand(
                                new DefinitionKey("restart-safe"), UUID.randomUUID(), operationId),
                        Instant.ofEpochMilli(now))
                .toCompletableFuture().join();
    }

    private static void assertBoundedDeliveryRecovery(Path database) {
        SQLiteStorageRuntime runtime = start(database);
        try {
            SQLiteDirectDeliveryRepository repository = new SQLiteDirectDeliveryRepository(runtime);
            int recovered = repository.moveExpiredClaimsToReview(
                            Instant.ofEpochMilli(2_011L), 1)
                    .toCompletableFuture().join();
            Page<DirectDeliveryRecord> remaining = repository
                    .listNonTerminal(PageRequest.first(10)).toCompletableFuture().join();
            assertEquals(1, recovered);
            assertEquals(2, remaining.items().size());
            assertEquals(1L, countState(remaining, DirectDeliveryState.REVIEW_REQUIRED));
            assertEquals(1L, countState(remaining, DirectDeliveryState.RESERVED));
            assertEquals(1, countCurrentState(runtime, "MISSING_UNRESOLVED"));
            assertEquals(1, repository.moveExpiredClaimsToReview(
                            Instant.ofEpochMilli(2_011L), 1)
                    .toCompletableFuture().join());
            assertEquals(2, countCurrentState(runtime, "MISSING_UNRESOLVED"));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static long countState(Page<DirectDeliveryRecord> records, DirectDeliveryState state) {
        return records.items().stream().filter(record -> record.state() == state).count();
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

    private static void seedDefinition(SQLiteStorageRuntime runtime, String key) {
        runtime.execute(connection -> {
                    UUID definitionId = UUID.randomUUID();
                    try (PreparedStatement definition = connection.prepareStatement(
                            "INSERT INTO lore_definitions(definition_id, lookup_key, display_name, "
                                    + "current_revision, created_at) VALUES (?, ?, ?, 1, 1)")) {
                        definition.setString(1, definitionId.toString());
                        definition.setString(2, key);
                        definition.setString(3, key);
                        definition.executeUpdate();
                    }
                    try (PreparedStatement revision = connection.prepareStatement(
                            "INSERT INTO lore_definition_revisions(definition_id, revision, "
                                    + "codec_version, template_blob, created_at) VALUES (?, 1, 1, ?, 1)")) {
                        revision.setString(1, definitionId.toString());
                        revision.setBytes(2, new byte[] {1});
                        revision.executeUpdate();
                    }
                    return null;
                })
                .toCompletableFuture()
                .join();
    }

    private static String currentLocationType(
            SQLiteStorageRuntime runtime,
            UUID instanceId) {
        return currentStateValue(runtime, instanceId, "location_type");
    }

    private static String currentContainerPath(
            SQLiteStorageRuntime runtime,
            UUID instanceId) {
        return currentStateValue(runtime, instanceId, "container_path");
    }

    private static String currentStateValue(
            SQLiteStorageRuntime runtime,
            UUID instanceId,
            String column) {
        return runtime.execute(connection -> {
                    String sql = switch (column) {
                        case "location_type" ->
                                "SELECT location_type FROM instance_current_state WHERE instance_id = ?";
                        case "container_path" ->
                                "SELECT container_path FROM instance_current_state WHERE instance_id = ?";
                        default -> throw new IllegalArgumentException("Unsupported current-state column");
                    };
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setString(1, instanceId.toString());
                        try (var resultSet = statement.executeQuery()) {
                            return resultSet.next() ? resultSet.getString(1) : null;
                        }
                    }
                })
                .toCompletableFuture()
                .join();
    }

    private static int countCurrentState(
            SQLiteStorageRuntime runtime,
            String state) {
        return runtime.execute(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT COUNT(*) FROM instance_current_state WHERE state = ?")) {
                        statement.setString(1, state);
                        try (var resultSet = statement.executeQuery()) {
                            return resultSet.next() ? resultSet.getInt(1) : 0;
                        }
                    }
                })
                .toCompletableFuture()
                .join();
    }

    private static int count(SQLiteStorageRuntime runtime, CountTable table) {
        return runtime.execute(connection -> count(connection, table)).toCompletableFuture().join();
    }

    private static int count(Connection connection, CountTable table) throws Exception {
        // The query is selected from a closed enum of test-only count statements.
        try (PreparedStatement statement = connection.prepareStatement(table.statementText()); // nosemgrep
             var resultSet = statement.executeQuery()) {
            return resultSet.getInt(1);
        }
    }

    private enum CountTable {
        LORE_INSTANCES("SELECT COUNT(*) FROM lore_instances"),
        DIRECT_DELIVERIES("SELECT COUNT(*) FROM direct_deliveries"),
        EXTERNAL_REQUESTS("SELECT COUNT(*) FROM external_delivery_requests"),
        OBSERVATIONS("SELECT COUNT(*) FROM instance_observations"),
        CURRENT_STATES("SELECT COUNT(*) FROM instance_current_state"),
        AUDIT_EVENTS("SELECT COUNT(*) FROM audit_events");

        private final String sqlText;

        CountTable(String sqlText) {
            this.sqlText = sqlText;
        }

        String statementText() {
            return sqlText;
        }
    }
}
