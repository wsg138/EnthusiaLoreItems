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
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsAppendsDeletesReusesKeyAndSurvivesRestart() {
        Path database = temporaryDirectory.resolve("definitions.db");
        LoreDefinitionId firstId = new LoreDefinitionId(UUID.randomUUID());
        LoreDefinition first = definition(firstId, "hourglass", "Vanguard's Hourglass", 1_000L);
        LoreDefinitionRevision firstRevision = revision(firstId, 1L, 1_000L, 1);
        LoreDefinitionId replacementId = new LoreDefinitionId(UUID.randomUUID());
        LoreDefinition replacement = definition(
                replacementId, "hourglass", "Restored Hourglass", 4_000L);
        LoreDefinitionRevision replacementRevision = revision(replacementId, 1L, 4_000L, 9);

        SQLiteStorageRuntime firstRuntime = start(database);
        try {
            SQLiteDefinitionRepository repository = new SQLiteDefinitionRepository(firstRuntime);
            repository.create(first, firstRevision).toCompletableFuture().join();

            assertThrows(
                    CompletionException.class,
                    () -> repository.create(replacement, replacementRevision)
                            .toCompletableFuture()
                            .join());

            LoreDefinitionRevision secondRevision = revision(firstId, 2L, 2_000L, 2);
            assertTrue(repository.appendRevision(
                            firstId, new TemplateRevision(1), secondRevision)
                    .toCompletableFuture()
                    .join());
            assertFalse(repository.appendRevision(
                            firstId, new TemplateRevision(1), secondRevision)
                    .toCompletableFuture()
                    .join());

            Page<LoreDefinitionRevision> revisions = repository
                    .listRevisions(firstId, PageRequest.first(1))
                    .toCompletableFuture()
                    .join();
            assertEquals(1, revisions.items().size());
            assertTrue(revisions.hasMore());
            assertEquals(new TemplateRevision(2), revisions.items().getFirst().revision());

            assertTrue(repository.markDeleted(
                            firstId, new TemplateRevision(2), Instant.ofEpochMilli(3_000L))
                    .toCompletableFuture()
                    .join());
            assertTrue(repository.findActiveByKey(new DefinitionKey("hourglass"))
                    .toCompletableFuture()
                    .join()
                    .isEmpty());
            assertFalse(repository.findById(firstId)
                    .toCompletableFuture()
                    .join()
                    .orElseThrow()
                    .active());

            repository.create(replacement, replacementRevision).toCompletableFuture().join();
            Page<LoreDefinition> active = repository
                    .listActive(PageRequest.first(1))
                    .toCompletableFuture()
                    .join();
            assertEquals(1, active.items().size());
            assertFalse(active.hasMore());
            assertEquals(replacementId, active.items().getFirst().id());
        } finally {
            firstRuntime.close(Duration.ofSeconds(5));
        }

        SQLiteStorageRuntime secondRuntime = start(database);
        try {
            SQLiteDefinitionRepository repository = new SQLiteDefinitionRepository(secondRuntime);
            assertFalse(repository.findById(firstId)
                    .toCompletableFuture()
                    .join()
                    .orElseThrow()
                    .active());
            assertEquals(replacementId, repository
                    .findActiveByKey(new DefinitionKey("hourglass"))
                    .toCompletableFuture()
                    .join()
                    .orElseThrow()
                    .id());
            assertEquals(2, repository
                    .findRevision(firstId, new TemplateRevision(2))
                    .toCompletableFuture()
                    .join()
                    .orElseThrow()
                    .templateBlob()[0]);
        } finally {
            secondRuntime.close(Duration.ofSeconds(5));
        }
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

            execute(runtime, "CREATE TRIGGER reject_initial_revision "
                    + "BEFORE INSERT ON lore_definition_revisions "
                    + "BEGIN SELECT RAISE(ABORT, 'forced initial failure'); END");
            assertThrows(
                    CompletionException.class,
                    () -> repository.create(definition, initial).toCompletableFuture().join());
            assertEquals(0, count(runtime, "lore_definitions"));
            execute(runtime, "DROP TRIGGER reject_initial_revision");

            repository.create(definition, initial).toCompletableFuture().join();
            execute(runtime, "CREATE TRIGGER reject_second_revision "
                    + "BEFORE INSERT ON lore_definition_revisions WHEN NEW.revision = 2 "
                    + "BEGIN SELECT RAISE(ABORT, 'forced append failure'); END");
            assertThrows(
                    CompletionException.class,
                    () -> repository.appendRevision(
                                    definitionId,
                                    new TemplateRevision(1),
                                    revision(definitionId, 2L, 2_000L, 2))
                            .toCompletableFuture()
                            .join());

            assertEquals(new TemplateRevision(1), repository
                    .findById(definitionId)
                    .toCompletableFuture()
                    .join()
                    .orElseThrow()
                    .currentRevision());
            assertEquals(1, count(runtime, "lore_definition_revisions"));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static LoreDefinition definition(
            LoreDefinitionId id, String key, String displayName, long createdAt) {
        return new LoreDefinition(
                id,
                new DefinitionKey(key),
                displayName,
                new TemplateRevision(1),
                createdAt,
                null);
    }

    private static LoreDefinitionRevision revision(
            LoreDefinitionId id, long revision, long createdAt, int templateByte) {
        return new LoreDefinitionRevision(
                id,
                new TemplateRevision(revision),
                1,
                new byte[] {(byte) templateByte},
                createdAt);
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

    private static void execute(SQLiteStorageRuntime runtime, String sql) {
        runtime.execute(connection -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                    return null;
                })
                .toCompletableFuture()
                .join();
    }

    private static int count(SQLiteStorageRuntime runtime, String table) {
        if (!table.equals("lore_definitions")
                && !table.equals("lore_definition_revisions")) {
            throw new IllegalArgumentException("Unexpected table");
        }
        return runtime.execute(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(
                                    "SELECT COUNT(*) FROM " + table);
                         var resultSet = statement.executeQuery()) {
                        return resultSet.getInt(1);
                    }
                })
                .toCompletableFuture()
                .join();
    }
}
