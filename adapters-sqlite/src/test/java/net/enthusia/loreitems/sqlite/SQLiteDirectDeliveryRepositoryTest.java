package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import net.enthusia.loreitems.application.DirectDeliveryRecord;
import net.enthusia.loreitems.application.ExternalDeliveryAcceptance;
import net.enthusia.loreitems.application.ExternalDeliveryCommand;
import net.enthusia.loreitems.application.ExternalDeliveryOutcome;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
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
            assertEquals(1, count(runtime, "lore_instances"));
            assertEquals(1, count(runtime, "direct_deliveries"));
            assertEquals(1, count(runtime, "external_delivery_requests"));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void claimTokenAndLiveLeaseFenceStateTransitions() throws Exception {
        Path database = temporaryDirectory.resolve("claim.db");
        SQLiteStorageRuntime runtime = start(database);
        try {
            seedDefinition(runtime, "claimable");
            SQLiteDirectDeliveryRepository repository = new SQLiteDirectDeliveryRepository(runtime);
            repository.acceptExternal(
                            new ExternalDeliveryCommand(
                                    new DefinitionKey("claimable"), UUID.randomUUID(), "claim-1"),
                            Instant.ofEpochMilli(1_000L))
                    .toCompletableFuture()
                    .join();

            Page<DirectDeliveryRecord> claimed = repository
                    .claimPending(
                            "worker-a",
                            Instant.ofEpochMilli(2_000L),
                            Duration.ofSeconds(30),
                            10)
                    .toCompletableFuture()
                    .join();
            Page<DirectDeliveryRecord> duplicateClaim = repository
                    .claimPending(
                            "worker-b",
                            Instant.ofEpochMilli(2_001L),
                            Duration.ofSeconds(30),
                            10)
                    .toCompletableFuture()
                    .join();

            assertEquals(1, claimed.items().size());
            assertTrue(duplicateClaim.items().isEmpty());
            UUID deliveryId = claimed.items().getFirst().deliveryId();
            assertFalse(repository.transitionClaimed(
                            deliveryId,
                            DirectDeliveryState.RESERVED,
                            DirectDeliveryState.APPLIED,
                            "worker-b",
                            Instant.ofEpochMilli(3_000L))
                    .toCompletableFuture()
                    .join());
            assertFalse(repository.transitionClaimed(
                            deliveryId,
                            DirectDeliveryState.RESERVED,
                            DirectDeliveryState.APPLIED,
                            "worker-a",
                            Instant.ofEpochMilli(32_001L))
                    .toCompletableFuture()
                    .join());
            assertTrue(repository.transitionClaimed(
                            deliveryId,
                            DirectDeliveryState.RESERVED,
                            DirectDeliveryState.APPLIED,
                            "worker-a",
                            Instant.ofEpochMilli(3_000L))
                    .toCompletableFuture()
                    .join());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void expiredClaimBecomesReviewRequiredAfterRestart() throws Exception {
        Path database = temporaryDirectory.resolve("restart.db");
        SQLiteStorageRuntime firstRuntime = start(database);
        seedDefinition(firstRuntime, "restart-safe");
        SQLiteDirectDeliveryRepository firstRepository =
                new SQLiteDirectDeliveryRepository(firstRuntime);
        firstRepository.acceptExternal(
                        new ExternalDeliveryCommand(
                                new DefinitionKey("restart-safe"), UUID.randomUUID(), "restart-1"),
                        Instant.ofEpochMilli(1_000L))
                .toCompletableFuture()
                .join();
        firstRepository.claimPending(
                        "worker-before-restart",
                        Instant.ofEpochMilli(2_000L),
                        Duration.ofMillis(10L),
                        10)
                .toCompletableFuture()
                .join();
        firstRuntime.close(Duration.ofSeconds(5));

        SQLiteStorageRuntime secondRuntime = start(database);
        try {
            SQLiteDirectDeliveryRepository secondRepository =
                    new SQLiteDirectDeliveryRepository(secondRuntime);
            int recovered = secondRepository
                    .moveExpiredClaimsToReview(Instant.ofEpochMilli(2_011L))
                    .toCompletableFuture()
                    .join();
            Page<DirectDeliveryRecord> remaining = secondRepository
                    .listNonTerminal(PageRequest.first(10))
                    .toCompletableFuture()
                    .join();

            assertEquals(1, recovered);
            assertEquals(1, remaining.items().size());
            assertEquals(DirectDeliveryState.REVIEW_REQUIRED, remaining.items().getFirst().state());
        } finally {
            secondRuntime.close(Duration.ofSeconds(5));
        }
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

    private static int count(SQLiteStorageRuntime runtime, String table) {
        return runtime.execute(connection -> count(connection, table)).toCompletableFuture().join();
    }

    private static int count(Connection connection, String table) throws Exception {
        if (!table.equals("lore_instances")
                && !table.equals("direct_deliveries")
                && !table.equals("external_delivery_requests")) {
            throw new IllegalArgumentException("Unexpected table");
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
             var resultSet = statement.executeQuery()) {
            return resultSet.getInt(1);
        }
    }
}
