package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreDefinitionRevision;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteDefinitionRepositoryTest {
    private static final String HOURGLASS_KEY = "hourglass";

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsAppendsDeletesReusesKeyAndSurvivesRestart() {
        Path database = temporaryDirectory.resolve("definitions.db");
        DefinitionScenario scenario = definitionScenario();
        exerciseFirstRuntime(database, scenario);
        assertRestartedState(database, scenario);
    }

    @Test
    void rollsBackDefinitionAndRevisionPromotionWhenRevisionInsertFails() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("rollback.db"));
        try {
            SQLiteDefinitionRepository repository = new SQLiteDefinitionRepository(runtime);
            LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
            LoreDefinition definition = definition(
                    definitionId, "rollback", "Rollback Test", 1_000L);
            LoreDefinitionRevision initial = revision(definitionId, 1L, 1_000L, 1);
            assertInitialInsertRollback(runtime, repository, definition, initial);
            assertAppendRollback(runtime, repository, definitionId, definition, initial);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static void exerciseFirstRuntime(Path database, DefinitionScenario scenario) {
        SQLiteStorageRuntime runtime = start(database);
        try {
            SQLiteDefinitionRepository repository = new SQLiteDefinitionRepository(runtime);
            repository.create(scenario.first(), scenario.firstRevision()).toCompletableFuture().join();
            assertDuplicateKeyRejected(repository, scenario);
            appendAndPageSecondRevision(repository, scenario.firstId());
            deleteAndReplace(repository, scenario);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static void assertDuplicateKeyRejected(
            SQLiteDefinitionRepository repository, DefinitionScenario scenario) {
        assertThrows(
                CompletionException.class,
                () -> repository.create(scenario.replacement(), scenario.replacementRevision())
                        .toCompletableFuture().join());
    }

    private static void appendAndPageSecondRevision(
            SQLiteDefinitionRepository repository, LoreDefinitionId firstId) {
        LoreDefinitionRevision secondRevision = revision(firstId, 2L, 2_000L, 2);
        assertTrue(repository.appendRevision(firstId, new TemplateRevision(1), secondRevision)
                .toCompletableFuture().join());
        assertFalse(repository.appendRevision(firstId, new TemplateRevision(1), secondRevision)
                .toCompletableFuture().join());
        Page<LoreDefinitionRevision> revisions = repository
                .listRevisions(firstId, PageRequest.first(1)).toCompletableFuture().join();
        assertEquals(1, revisions.items().size());
        assertTrue(revisions.hasMore());
        assertEquals(new TemplateRevision(2), revisions.items().getFirst().revision());
    }

    private static void deleteAndReplace(
            SQLiteDefinitionRepository repository, DefinitionScenario scenario) {
        assertTrue(repository.markDeleted(
                        scenario.firstId(), new TemplateRevision(2), Instant.ofEpochMilli(3_000L))
                .toCompletableFuture().join());
        assertTrue(repository.findActiveByKey(new DefinitionKey(HOURGLASS_KEY))
                .toCompletableFuture().join().isEmpty());
        assertFalse(repository.findById(scenario.firstId())
                .toCompletableFuture().join().orElseThrow().active());
        repository.create(scenario.replacement(), scenario.replacementRevision())
                .toCompletableFuture().join();
        Page<LoreDefinition> active = repository.listActive(PageRequest.first(1))
                .toCompletableFuture().join();
        assertEquals(1, active.items().size());
        assertFalse(active.hasMore());
        assertEquals(scenario.replacementId(), active.items().getFirst().id());
    }

    private static void assertRestartedState(Path database, DefinitionScenario scenario) {
        SQLiteStorageRuntime runtime = start(database);
        try {
            SQLiteDefinitionRepository repository = new SQLiteDefinitionRepository(runtime);
            assertFalse(repository.findById(scenario.firstId())
                    .toCompletableFuture().join().orElseThrow().active());
            assertEquals(scenario.replacementId(), repository
                    .findActiveByKey(new DefinitionKey(HOURGLASS_KEY))
                    .toCompletableFuture().join().orElseThrow().id());
            assertEquals(2, repository.findRevision(scenario.firstId(), new TemplateRevision(2))
                    .toCompletableFuture().join().orElseThrow().templateBlob()[0]);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static void assertInitialInsertRollback(
            SQLiteStorageRuntime runtime,
            SQLiteDefinitionRepository repository,
            LoreDefinition definition,
            LoreDefinitionRevision initial) {
        executeFixture(runtime, SqlFixture.REJECT_INITIAL_REVISION);
        assertThrows(
                CompletionException.class,
                () -> repository.create(definition, initial).toCompletableFuture().join());
        assertEquals(0, countDefinitions(runtime));
        executeFixture(runtime, SqlFixture.DROP_INITIAL_REJECTION);
    }

    private static void assertAppendRollback(
            SQLiteStorageRuntime runtime,
            SQLiteDefinitionRepository repository,
            LoreDefinitionId definitionId,
            LoreDefinition definition,
            LoreDefinitionRevision initial) {
        repository.create(definition, initial).toCompletableFuture().join();
        executeFixture(runtime, SqlFixture.REJECT_SECOND_REVISION);
        assertThrows(
                CompletionException.class,
                () -> repository.appendRevision(
                                definitionId,
                                new TemplateRevision(1),
                                revision(definitionId, 2L, 2_000L, 2))
                        .toCompletableFuture().join());
        assertEquals(new TemplateRevision(1), repository.findById(definitionId)
                .toCompletableFuture().join().orElseThrow().currentRevision());
        assertEquals(1, countRevisions(runtime));
    }

    private static DefinitionScenario definitionScenario() {
        LoreDefinitionId firstId = new LoreDefinitionId(UUID.randomUUID());
        LoreDefinition first = definition(
                firstId, HOURGLASS_KEY, "Vanguard's Hourglass", 1_000L);
        LoreDefinitionId replacementId = new LoreDefinitionId(UUID.randomUUID());
        LoreDefinition replacement = definition(
                replacementId, HOURGLASS_KEY, "Restored Hourglass", 4_000L);
        return new DefinitionScenario(
                firstId,
                first,
                revision(firstId, 1L, 1_000L, 1),
                replacementId,
                replacement,
                revision(replacementId, 1L, 4_000L, 9));
    }

    private static LoreDefinition definition(
            LoreDefinitionId id, String key, String displayName, long createdAt) {
        return new LoreDefinition(
                id, new DefinitionKey(key), displayName, new TemplateRevision(1), createdAt, null);
    }

    private static LoreDefinitionRevision revision(
            LoreDefinitionId id, long revision, long createdAt, int templateByte) {
        return new LoreDefinitionRevision(
                id, new TemplateRevision(revision), 1, new byte[] {(byte) templateByte}, createdAt);
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

    private static void executeFixture(SQLiteStorageRuntime runtime, SqlFixture fixture) {
        runtime.execute(connection -> {
                    try (Statement statement = connection.createStatement()) {
                        // The SQL is a closed enum of test-only fault-injection fixtures.
                        statement.execute(fixture.statementText()); // nosemgrep
                    }
                    return null;
                })
                .toCompletableFuture().join();
    }

    private static int countDefinitions(SQLiteStorageRuntime runtime) {
        return count(runtime, "SELECT COUNT(*) FROM lore_definitions");
    }

    private static int countRevisions(SQLiteStorageRuntime runtime) {
        return count(runtime, "SELECT COUNT(*) FROM lore_definition_revisions");
    }

    private static int count(SQLiteStorageRuntime runtime, String fixedSql) {
        return runtime.execute(connection -> {
                    // Callers provide only compile-time constant count queries in this test class.
                    try (PreparedStatement statement = connection.prepareStatement(fixedSql); // nosemgrep
                         var resultSet = statement.executeQuery()) {
                        return resultSet.getInt(1);
                    }
                })
                .toCompletableFuture().join();
    }

    private enum SqlFixture {
        REJECT_INITIAL_REVISION(
                "CREATE TRIGGER reject_initial_revision "
                        + "BEFORE INSERT ON lore_definition_revisions "
                        + "BEGIN SELECT RAISE(ABORT, 'forced initial failure'); END"),
        DROP_INITIAL_REJECTION("DROP TRIGGER reject_initial_revision"),
        REJECT_SECOND_REVISION(
                "CREATE TRIGGER reject_second_revision "
                        + "BEFORE INSERT ON lore_definition_revisions WHEN NEW.revision = 2 "
                        + "BEGIN SELECT RAISE(ABORT, 'forced append failure'); END");

        private final String statementText;

        SqlFixture(String statementText) {
            this.statementText = statementText;
        }

        String statementText() {
            return statementText;
        }
    }

    private record DefinitionScenario(
            LoreDefinitionId firstId,
            LoreDefinition first,
            LoreDefinitionRevision firstRevision,
            LoreDefinitionId replacementId,
            LoreDefinition replacement,
            LoreDefinitionRevision replacementRevision) {
    }
}
