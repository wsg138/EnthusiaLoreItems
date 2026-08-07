package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.PreparedDirectDelivery;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.DirectDeliveryState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteDirectDeliveryFailureMatrixTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void failureImmediatelyBeforeIntentCommitRollsBackAndRetryCreatesExactlyOneDelivery()
            throws Exception {
        Path database = temporaryDirectory.resolve("before-intent.db");
        try (SQLiteFailureInjectionHarness harness = new SQLiteFailureInjectionHarness(database)) {
            seedDefinition(harness.runtime(), "before-intent");
            ExternalDeliveryCommand command = command("before-intent", "before-intent-op");
            SQLiteDirectDeliveryRepository beforeRestart = repository(harness);
            harness.arm(SQLiteFailureInjectionHarness.FailurePoint.BEFORE_INSTANCE_INSERT);

            assertThrows(CompletionException.class, () -> beforeRestart
                    .acceptExternal(command, Instant.ofEpochMilli(1_000L))
                    .toCompletableFuture()
                    .join());
            assertEquals(0, count(harness.runtime(), CountTable.LORE_INSTANCES));
            assertEquals(0, count(harness.runtime(), CountTable.DIRECT_DELIVERIES));
            assertEquals(0, count(harness.runtime(), CountTable.EXTERNAL_REQUESTS));

            harness.restart();
            harness.disarm(SQLiteFailureInjectionHarness.FailurePoint.BEFORE_INSTANCE_INSERT);
            SQLiteDirectDeliveryRepository afterRestart = repository(harness);
            ExternalDeliveryAcceptance accepted = afterRestart
                    .acceptExternal(command, Instant.ofEpochMilli(2_000L))
                    .toCompletableFuture()
                    .join();

            assertEquals(ExternalDeliveryOutcome.ACCEPTED_QUEUED, accepted.outcome());
            assertEquals(1, count(harness.runtime(), CountTable.LORE_INSTANCES));
            assertEquals(1, count(harness.runtime(), CountTable.DIRECT_DELIVERIES));
            assertEquals(1, count(harness.runtime(), CountTable.EXTERNAL_REQUESTS));
        }
    }

    @Test
    void restartAfterIntentBeforeClaimReplaysIntentAndClaimsOnlyTheExistingDelivery()
            throws Exception {
        Path database = temporaryDirectory.resolve("after-intent.db");
        try (SQLiteFailureInjectionHarness harness = new SQLiteFailureInjectionHarness(database)) {
            seedDefinition(harness.runtime(), "after-intent");
            ExternalDeliveryCommand command = command("after-intent", "after-intent-op");
            SQLiteDirectDeliveryRepository repository = repository(harness);
            ExternalDeliveryAcceptance first = repository
                    .acceptExternal(command, Instant.ofEpochMilli(1_000L))
                    .toCompletableFuture()
                    .join();

            harness.restart();
            repository = repository(harness);
            ExternalDeliveryAcceptance replay = repository
                    .acceptExternal(command, Instant.ofEpochMilli(2_000L))
                    .toCompletableFuture()
                    .join();
            Page<PreparedDirectDelivery> claimed = repository
                    .claimPreparedPending(
                            "after-intent-worker",
                            Instant.ofEpochMilli(2_100L),
                            Duration.ofSeconds(30),
                            10)
                    .toCompletableFuture()
                    .join();

            assertEquals(ExternalDeliveryOutcome.ALREADY_ACCEPTED, replay.outcome());
            assertEquals(first.deliveryId(), replay.deliveryId());
            assertEquals(1, claimed.items().size());
            assertEquals(first.deliveryId().orElseThrow(), claimed.items().getFirst().deliveryId());
            assertEquals(1, count(harness.runtime(), CountTable.LORE_INSTANCES));
            assertEquals(1, count(harness.runtime(), CountTable.DIRECT_DELIVERIES));
        }
    }

    @Test
    void restartAfterClaimBeforePaperApplyMovesExpiredAmbiguousClaimToReview()
            throws Exception {
        Path database = temporaryDirectory.resolve("after-claim.db");
        try (SQLiteFailureInjectionHarness harness = new SQLiteFailureInjectionHarness(database)) {
            seedDefinition(harness.runtime(), "after-claim");
            SQLiteDirectDeliveryRepository repository = repository(harness);
            repository.acceptExternal(
                            command("after-claim", "after-claim-op"),
                            Instant.ofEpochMilli(1_000L))
                    .toCompletableFuture()
                    .join();
            Page<PreparedDirectDelivery> claimed = repository
                    .claimPreparedPending(
                            "after-claim-worker",
                            Instant.ofEpochMilli(2_000L),
                            Duration.ofMillis(10L),
                            10)
                    .toCompletableFuture()
                    .join();
            assertEquals(1, claimed.items().size());

            harness.restart();
            repository = repository(harness);
            assertEquals(1, repository
                    .moveExpiredClaimsToReview(Instant.ofEpochMilli(2_011L), 10)
                    .toCompletableFuture()
                    .join());
            Page<DirectDeliveryRecord> remaining = repository
                    .listNonTerminal(PageRequest.first(10))
                    .toCompletableFuture()
                    .join();

            assertEquals(1, remaining.items().size());
            assertEquals(DirectDeliveryState.REVIEW_REQUIRED, remaining.items().getFirst().state());
            assertTrue(repository
                    .claimPreparedPending(
                            "unsafe-retry-worker",
                            Instant.ofEpochMilli(2_012L),
                            Duration.ofSeconds(30),
                            10)
                    .toCompletableFuture()
                    .join()
                    .items()
                    .isEmpty());
            assertEquals(1, countCurrentState(harness.runtime(), "MISSING_UNRESOLVED"));
        }
    }

    @Test
    void verificationCommitFailureRollsBackThenRestartEscalatesClaimToReview()
            throws Exception {
        Path database = temporaryDirectory.resolve("verification-failure.db");
        try (SQLiteFailureInjectionHarness harness = new SQLiteFailureInjectionHarness(database)) {
            seedDefinition(harness.runtime(), "verification-failure");
            SQLiteDirectDeliveryRepository beforeRestart = repository(harness);
            beforeRestart.acceptExternal(
                            command("verification-failure", "verification-op"),
                            Instant.ofEpochMilli(1_000L))
                    .toCompletableFuture()
                    .join();
            PreparedDirectDelivery delivery = beforeRestart
                    .claimPreparedPending(
                            "verification-worker",
                            Instant.ofEpochMilli(2_000L),
                            Duration.ofMillis(10L),
                            10)
                    .toCompletableFuture()
                    .join()
                    .items()
                    .getFirst();
            harness.arm(SQLiteFailureInjectionHarness.FailurePoint.BEFORE_AUDIT_INSERT);

            assertThrows(CompletionException.class, () -> beforeRestart
                    .completeClaimed(
                            delivery,
                            4,
                            "a".repeat(64),
                            Instant.ofEpochMilli(2_005L))
                    .toCompletableFuture()
                    .join());
            Page<DirectDeliveryRecord> afterFailure = beforeRestart
                    .listNonTerminal(PageRequest.first(10))
                    .toCompletableFuture()
                    .join();
            assertEquals(1, afterFailure.items().size());
            assertEquals(DirectDeliveryState.RESERVED, afterFailure.items().getFirst().state());
            assertEquals(1, count(harness.runtime(), CountTable.OBSERVATIONS));
            assertEquals(1, count(harness.runtime(), CountTable.AUDIT_EVENTS));

            harness.restart();
            harness.disarm(SQLiteFailureInjectionHarness.FailurePoint.BEFORE_AUDIT_INSERT);
            SQLiteDirectDeliveryRepository afterRestart = repository(harness);
            assertEquals(1, afterRestart
                    .moveExpiredClaimsToReview(Instant.ofEpochMilli(2_011L), 10)
                    .toCompletableFuture()
                    .join());
            Page<DirectDeliveryRecord> recovered = afterRestart
                    .listNonTerminal(PageRequest.first(10))
                    .toCompletableFuture()
                    .join();

            assertEquals(DirectDeliveryState.REVIEW_REQUIRED, recovered.items().getFirst().state());
            assertEquals(1, count(harness.runtime(), CountTable.OBSERVATIONS));
            assertEquals(2, count(harness.runtime(), CountTable.AUDIT_EVENTS));
            assertEquals(1, countCurrentState(harness.runtime(), "MISSING_UNRESOLVED"));
        }
    }

    @Test
    void restartAfterTerminalCommitDoesNotRequeueOrDuplicateCompletedDelivery()
            throws Exception {
        Path database = temporaryDirectory.resolve("after-terminal.db");
        try (SQLiteFailureInjectionHarness harness = new SQLiteFailureInjectionHarness(database)) {
            seedDefinition(harness.runtime(), "after-terminal");
            ExternalDeliveryCommand command = command("after-terminal", "after-terminal-op");
            SQLiteDirectDeliveryRepository repository = repository(harness);
            ExternalDeliveryAcceptance accepted = repository
                    .acceptExternal(command, Instant.ofEpochMilli(1_000L))
                    .toCompletableFuture()
                    .join();
            PreparedDirectDelivery delivery = repository
                    .claimPreparedPending(
                            "terminal-worker",
                            Instant.ofEpochMilli(2_000L),
                            Duration.ofSeconds(30),
                            10)
                    .toCompletableFuture()
                    .join()
                    .items()
                    .getFirst();
            assertTrue(repository
                    .completeClaimed(
                            delivery,
                            8,
                            "b".repeat(64),
                            Instant.ofEpochMilli(3_000L))
                    .toCompletableFuture()
                    .join());

            harness.restart();
            repository = repository(harness);
            ExternalDeliveryAcceptance replay = repository
                    .acceptExternal(command, Instant.ofEpochMilli(4_000L))
                    .toCompletableFuture()
                    .join();

            assertEquals(ExternalDeliveryOutcome.ALREADY_ACCEPTED, replay.outcome());
            assertEquals(accepted.deliveryId(), replay.deliveryId());
            assertTrue(repository
                    .claimPreparedPending(
                            "post-terminal-worker",
                            Instant.ofEpochMilli(4_001L),
                            Duration.ofSeconds(30),
                            10)
                    .toCompletableFuture()
                    .join()
                    .items()
                    .isEmpty());
            assertTrue(repository
                    .listNonTerminal(PageRequest.first(10))
                    .toCompletableFuture()
                    .join()
                    .items()
                    .isEmpty());
            assertEquals(1, count(harness.runtime(), CountTable.LORE_INSTANCES));
            assertEquals(1, count(harness.runtime(), CountTable.DIRECT_DELIVERIES));
        }
    }

    private static SQLiteDirectDeliveryRepository repository(SQLiteFailureInjectionHarness harness) {
        return new SQLiteDirectDeliveryRepository(harness.runtime());
    }

    private static ExternalDeliveryCommand command(String definitionKey, String operationId) {
        return new ExternalDeliveryCommand(
                new DefinitionKey(definitionKey),
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                operationId);
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

    private static int countCurrentState(SQLiteStorageRuntime runtime, String state) {
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
