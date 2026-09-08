package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import net.enthusia.loreitems.application.ExternalDeliveryAcceptance;
import net.enthusia.loreitems.application.ExternalDeliveryCommand;
import net.enthusia.loreitems.application.ExternalDeliveryOutcome;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.domain.DefinitionKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteExternalDeliveryRecoveryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void unknownDefinitionCanBeAcceptedAfterDefinitionIsCreatedWithSameOperationId() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("recover-unknown.db"));
        try {
            SQLiteDirectDeliveryRepository repository = new SQLiteDirectDeliveryRepository(runtime);
            UUID playerId = UUID.fromString("11111111-2222-3333-4444-555555555555");
            ExternalDeliveryCommand command = new ExternalDeliveryCommand(
                    new DefinitionKey("recoverable"), playerId, "tags:recoverable:1");

            ExternalDeliveryAcceptance first = repository
                    .acceptExternal(command, Instant.ofEpochMilli(1_000L))
                    .toCompletableFuture()
                    .join();

            assertEquals(ExternalDeliveryOutcome.UNKNOWN_DEFINITION, first.outcome());
            assertTrue(first.deliveryId().isEmpty());
            assertEquals(1, count(runtime, CountTable.EXTERNAL_REQUESTS));
            assertEquals(0, count(runtime, CountTable.LORE_INSTANCES));
            assertEquals(0, count(runtime, CountTable.DIRECT_DELIVERIES));
            assertEquals(0, count(runtime, CountTable.OBSERVATIONS));
            assertEquals(0, count(runtime, CountTable.CURRENT_STATES));
            assertEquals(0, count(runtime, CountTable.AUDIT_EVENTS));

            seedDefinition(runtime, "recoverable");

            ExternalDeliveryAcceptance accepted = repository
                    .acceptExternal(command, Instant.ofEpochMilli(2_000L))
                    .toCompletableFuture()
                    .join();
            ExternalDeliveryAcceptance replay = repository
                    .acceptExternal(command, Instant.ofEpochMilli(3_000L))
                    .toCompletableFuture()
                    .join();

            assertEquals(ExternalDeliveryOutcome.ACCEPTED_QUEUED, accepted.outcome());
            assertEquals(ExternalDeliveryOutcome.ALREADY_ACCEPTED, replay.outcome());
            assertTrue(accepted.deliveryId().isPresent());
            assertEquals(accepted.deliveryId(), replay.deliveryId());
            assertEquals(1, count(runtime, CountTable.EXTERNAL_REQUESTS));
            assertEquals(1, count(runtime, CountTable.LORE_INSTANCES));
            assertEquals(1, count(runtime, CountTable.DIRECT_DELIVERIES));
            assertEquals(1, count(runtime, CountTable.OBSERVATIONS));
            assertEquals(1, count(runtime, CountTable.CURRENT_STATES));
            assertEquals(1, count(runtime, CountTable.AUDIT_EVENTS));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void unknownDefinitionRecoveryStillRejectsOperationIdReuseWithDifferentArguments() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("recover-fenced.db"));
        try {
            SQLiteDirectDeliveryRepository repository = new SQLiteDirectDeliveryRepository(runtime);
            UUID originalPlayer = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
            String operationId = "tags:recoverable:2";
            ExternalDeliveryCommand original = new ExternalDeliveryCommand(
                    new DefinitionKey("recoverable"), originalPlayer, operationId);

            assertEquals(
                    ExternalDeliveryOutcome.UNKNOWN_DEFINITION,
                    repository.acceptExternal(original, Instant.ofEpochMilli(1_000L))
                            .toCompletableFuture()
                            .join()
                            .outcome());

            seedDefinition(runtime, "recoverable");
            seedDefinition(runtime, "other");

            ExternalDeliveryCommand wrongPlayer = new ExternalDeliveryCommand(
                    new DefinitionKey("recoverable"), UUID.randomUUID(), operationId);
            ExternalDeliveryCommand wrongDefinition = new ExternalDeliveryCommand(
                    new DefinitionKey("other"), originalPlayer, operationId);

            assertEquals(
                    ExternalDeliveryOutcome.VALIDATION_FAILURE,
                    repository.acceptExternal(wrongPlayer, Instant.ofEpochMilli(2_000L))
                            .toCompletableFuture()
                            .join()
                            .outcome());
            assertEquals(
                    ExternalDeliveryOutcome.VALIDATION_FAILURE,
                    repository.acceptExternal(wrongDefinition, Instant.ofEpochMilli(2_100L))
                            .toCompletableFuture()
                            .join()
                            .outcome());
            assertEquals(0, count(runtime, CountTable.LORE_INSTANCES));
            assertEquals(0, count(runtime, CountTable.DIRECT_DELIVERIES));

            ExternalDeliveryAcceptance accepted = repository
                    .acceptExternal(original, Instant.ofEpochMilli(3_000L))
                    .toCompletableFuture()
                    .join();

            assertEquals(ExternalDeliveryOutcome.ACCEPTED_QUEUED, accepted.outcome());
            assertEquals(1, count(runtime, CountTable.EXTERNAL_REQUESTS));
            assertEquals(1, count(runtime, CountTable.LORE_INSTANCES));
            assertEquals(1, count(runtime, CountTable.DIRECT_DELIVERIES));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static SQLiteStorageRuntime start(Path database) {
        MetricsPort metrics = MetricsPort.noOp();
        SQLiteStorageRuntime runtime = new SQLiteStorageRuntime(
                new SQLiteConnectionFactory(database, 5_000),
                new MigrationRunner(),
                new BoundedDatabaseExecutor("external-recovery-test", 32, metrics),
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

    private static int count(SQLiteStorageRuntime runtime, CountTable table) {
        return runtime.execute(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(table.statementText());
                            var resultSet = statement.executeQuery()) {
                        return resultSet.next() ? resultSet.getInt(1) : 0;
                    }
                })
                .toCompletableFuture()
                .join();
    }

    private enum CountTable {
        EXTERNAL_REQUESTS("SELECT COUNT(*) FROM external_delivery_requests"),
        LORE_INSTANCES("SELECT COUNT(*) FROM lore_instances"),
        DIRECT_DELIVERIES("SELECT COUNT(*) FROM direct_deliveries"),
        OBSERVATIONS("SELECT COUNT(*) FROM instance_observations"),
        CURRENT_STATES("SELECT COUNT(*) FROM instance_current_state"),
        AUDIT_EVENTS("SELECT COUNT(*) FROM audit_events");

        private final String statementText;

        CountTable(String statementText) {
            this.statementText = statementText;
        }

        private String statementText() {
            return statementText;
        }
    }
}
