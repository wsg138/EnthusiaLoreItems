package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.StorageState;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.DeletedDefinitionMarker;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreDefinitionRevision;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteDeletedDefinitionMarkerRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsImmutableMarkerAndAuditAcrossRestart() {
        Path database = temporaryDirectory.resolve("markers.db");
        LoreDefinitionId definitionId;
        DeletedDefinitionMarker expected;

        SQLiteStorageRuntime firstRuntime = start(database);
        try {
            DefinitionKey lookupKey = new DefinitionKey("historic_item");
            definitionId = createDefinition(firstRuntime, lookupKey, 1_000L);
            expected = deleteWithMarker(firstRuntime, definitionId, lookupKey, 2_000L);
            SQLiteDeletedDefinitionMarkerRepository repository =
                    new SQLiteDeletedDefinitionMarkerRepository(firstRuntime);

            assertEquals(
                    expected,
                    repository.findByDefinitionId(definitionId)
                            .toCompletableFuture()
                            .join()
                            .orElseThrow());
            var auditPage = new SQLiteAuditRepository(firstRuntime)
                    .listByAggregate(
                            "lore_definition",
                            definitionId.value().toString(),
                            PageRequest.first(10))
                    .toCompletableFuture()
                    .join();
            assertEquals(1, auditPage.items().size());
            assertEquals(2_000L, auditPage.items().getFirst().occurredAtEpochMillis());

            assertThrows(
                    CompletionException.class,
                    () -> executeMarkerMutation(
                            firstRuntime,
                            "UPDATE deleted_definition_markers SET lookup_key = ? "
                                    + "WHERE definition_id = ?",
                            "changed",
                            definitionId));
            assertThrows(
                    CompletionException.class,
                    () -> executeMarkerMutation(
                            firstRuntime,
                            "DELETE FROM deleted_definition_markers WHERE lookup_key = ? "
                                    + "AND definition_id = ?",
                            lookupKey.value(),
                            definitionId));
        } finally {
            firstRuntime.close(Duration.ofSeconds(5));
        }

        SQLiteStorageRuntime restartedRuntime = start(database);
        try {
            assertEquals(
                    expected,
                    new SQLiteDeletedDefinitionMarkerRepository(restartedRuntime)
                            .findByDefinitionId(definitionId)
                            .toCompletableFuture()
                            .join()
                            .orElseThrow());
        } finally {
            restartedRuntime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void pagesRecentAndLookupKeyHistoryWithoutCollapsingReusedKeys() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("history.db"));
        try {
            DefinitionKey repeatedKey = new DefinitionKey("reused_key");
            LoreDefinitionId firstId = createDefinition(runtime, repeatedKey, 1_000L);
            DeletedDefinitionMarker first =
                    deleteWithMarker(runtime, firstId, repeatedKey, 2_000L);

            DefinitionKey otherKey = new DefinitionKey("other_key");
            LoreDefinitionId otherId = createDefinition(runtime, otherKey, 1_100L);
            DeletedDefinitionMarker other =
                    deleteWithMarker(runtime, otherId, otherKey, 3_000L);

            LoreDefinitionId secondId = createDefinition(runtime, repeatedKey, 1_200L);
            DeletedDefinitionMarker second =
                    deleteWithMarker(runtime, secondId, repeatedKey, 4_000L);

            SQLiteDeletedDefinitionMarkerRepository repository =
                    new SQLiteDeletedDefinitionMarkerRepository(runtime);
            var firstKeyPage = repository.listByLookupKey(repeatedKey, PageRequest.first(1))
                    .toCompletableFuture()
                    .join();
            assertEquals(java.util.List.of(second), firstKeyPage.items());
            assertTrue(firstKeyPage.hasMore());

            var secondKeyPage = repository.listByLookupKey(
                            repeatedKey, firstKeyPage.hasMore() ? PageRequest.first(1).next() : null)
                    .toCompletableFuture()
                    .join();
            assertEquals(java.util.List.of(first), secondKeyPage.items());
            assertFalse(secondKeyPage.hasMore());

            var recent = repository.listRecent(PageRequest.first(2))
                    .toCompletableFuture()
                    .join();
            assertEquals(java.util.List.of(second, other), recent.items());
            assertTrue(recent.hasMore());

            LoreDefinitionId activeId = createDefinition(
                    runtime, new DefinitionKey("still_active"), 5_000L);
            assertThrows(
                    CompletionException.class,
                    () -> repository.create(new DeletedDefinitionMarker(
                                    activeId,
                                    new DefinitionKey("still_active"),
                                    6_000L))
                            .toCompletableFuture()
                            .join());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static LoreDefinitionId createDefinition(
            SQLiteStorageRuntime runtime, DefinitionKey key, long createdAt) {
        LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
        LoreDefinition definition = new LoreDefinition(
                definitionId,
                key,
                "Deleted marker test",
                new TemplateRevision(1),
                createdAt,
                null);
        LoreDefinitionRevision revision = new LoreDefinitionRevision(
                definitionId,
                new TemplateRevision(1),
                1,
                new byte[] {1},
                createdAt);
        new SQLiteDefinitionRepository(runtime)
                .create(definition, revision)
                .toCompletableFuture()
                .join();
        return definitionId;
    }

    private static DeletedDefinitionMarker deleteWithMarker(
            SQLiteStorageRuntime runtime,
            LoreDefinitionId definitionId,
            DefinitionKey lookupKey,
            long deletedAt) {
        return new SQLiteUnitOfWork(runtime)
                .execute(context -> {
                    boolean deleted = context.definitions().markDeleted(
                            definitionId,
                            new TemplateRevision(1),
                            Instant.ofEpochMilli(deletedAt));
                    if (!deleted) {
                        throw new IllegalStateException("Expected definition deletion to succeed");
                    }
                    context.audit().append(AuditEventRecord.pending(
                            "lore_definition",
                            definitionId.value().toString(),
                            "definition_deleted",
                            "system",
                            null,
                            "{\"reason\":\"deleted-marker-test\"}",
                            deletedAt));
                    DeletedDefinitionMarker marker =
                            new DeletedDefinitionMarker(definitionId, lookupKey, deletedAt);
                    context.deletedDefinitionMarkers().create(marker);
                    return marker;
                })
                .toCompletableFuture()
                .join();
    }

    private static SQLiteStorageRuntime start(Path database) {
        MetricsPort metrics = MetricsPort.noOp();
        SQLiteStorageRuntime runtime = new SQLiteStorageRuntime(
                new SQLiteConnectionFactory(database, 5_000),
                new MigrationRunner(),
                new BoundedDatabaseExecutor("test-database", 32, metrics),
                metrics);
        assertEquals(
                StorageState.READ_WRITE,
                runtime.start().toCompletableFuture().join().state());
        return runtime;
    }

    private static void executeMarkerMutation(
            SQLiteStorageRuntime runtime,
            String sql,
            String lookupKey,
            LoreDefinitionId definitionId) {
        runtime.execute(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setString(1, lookupKey);
                        statement.setString(2, definitionId.value().toString());
                        statement.executeUpdate();
                    }
                    return null;
                })
                .toCompletableFuture()
                .join();
    }
}
