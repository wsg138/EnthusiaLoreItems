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
        MarkerScenario scenario = createAndVerifyMarker(database);
        assertMarkerAfterRestart(database, scenario);
    }

    @Test
    void pagesRecentAndLookupKeyHistoryWithoutCollapsingReusedKeys() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("history.db"));
        try {
            MarkerHistory history = createMarkerHistory(runtime);
            assertPagedHistory(runtime, history);
            assertActiveDefinitionCannotBeMarked(runtime);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static MarkerScenario createAndVerifyMarker(Path database) {
        SQLiteStorageRuntime runtime = start(database);
        try {
            DefinitionKey lookupKey = new DefinitionKey("historic_item");
            LoreDefinitionId definitionId = createDefinition(runtime, lookupKey, 1_000L);
            DeletedDefinitionMarker marker =
                    deleteWithMarker(runtime, definitionId, lookupKey, 2_000L);
            SQLiteDeletedDefinitionMarkerRepository repository =
                    new SQLiteDeletedDefinitionMarkerRepository(runtime);
            repository.create(marker).toCompletableFuture().join();
            assertEquals(marker, repository.findByDefinitionId(definitionId)
                    .toCompletableFuture().join().orElseThrow());
            assertAudit(runtime, definitionId);
            assertMarkerIsImmutable(runtime, lookupKey, definitionId);
            return new MarkerScenario(definitionId, marker);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static void assertAudit(SQLiteStorageRuntime runtime, LoreDefinitionId definitionId) {
        var auditPage = new SQLiteAuditRepository(runtime).listByAggregate(
                        "lore_definition", definitionId.value().toString(), PageRequest.first(10))
                .toCompletableFuture().join();
        assertEquals(1, auditPage.items().size());
        assertEquals(2_000L, auditPage.items().getFirst().occurredAtEpochMillis());
    }

    private static void assertMarkerIsImmutable(
            SQLiteStorageRuntime runtime, DefinitionKey lookupKey, LoreDefinitionId definitionId) {
        assertThrows(CompletionException.class, () -> executeMarkerMutation(
                runtime, MarkerMutation.UPDATE_KEY, "changed", definitionId));
        assertThrows(CompletionException.class, () -> executeMarkerMutation(
                runtime, MarkerMutation.DELETE_MARKER, lookupKey.value(), definitionId));
    }

    private static void assertMarkerAfterRestart(Path database, MarkerScenario scenario) {
        SQLiteStorageRuntime runtime = start(database);
        try {
            assertEquals(scenario.marker(), new SQLiteDeletedDefinitionMarkerRepository(runtime)
                    .findByDefinitionId(scenario.definitionId())
                    .toCompletableFuture().join().orElseThrow());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static MarkerHistory createMarkerHistory(SQLiteStorageRuntime runtime) {
        DefinitionKey repeatedKey = new DefinitionKey("reused_key");
        LoreDefinitionId firstId = createDefinition(runtime, repeatedKey, 1_000L);
        DeletedDefinitionMarker first = deleteWithMarker(runtime, firstId, repeatedKey, 2_000L);
        DefinitionKey otherKey = new DefinitionKey("other_key");
        LoreDefinitionId otherId = createDefinition(runtime, otherKey, 1_100L);
        DeletedDefinitionMarker other = deleteWithMarker(runtime, otherId, otherKey, 3_000L);
        LoreDefinitionId secondId = createDefinition(runtime, repeatedKey, 1_200L);
        DeletedDefinitionMarker second = deleteWithMarker(runtime, secondId, repeatedKey, 4_000L);
        return new MarkerHistory(repeatedKey, first, other, second);
    }

    private static void assertPagedHistory(SQLiteStorageRuntime runtime, MarkerHistory history) {
        SQLiteDeletedDefinitionMarkerRepository repository =
                new SQLiteDeletedDefinitionMarkerRepository(runtime);
        PageRequest oneItemPage = PageRequest.first(1);
        var firstKeyPage = repository.listByLookupKey(history.repeatedKey(), oneItemPage)
                .toCompletableFuture().join();
        assertEquals(java.util.List.of(history.second()), firstKeyPage.items());
        assertTrue(firstKeyPage.hasMore());
        var secondKeyPage = repository.listByLookupKey(history.repeatedKey(), oneItemPage.next())
                .toCompletableFuture().join();
        assertEquals(java.util.List.of(history.first()), secondKeyPage.items());
        assertFalse(secondKeyPage.hasMore());
        var recent = repository.listRecent(PageRequest.first(2)).toCompletableFuture().join();
        assertEquals(java.util.List.of(history.second(), history.other()), recent.items());
        assertTrue(recent.hasMore());
    }

    private static void assertActiveDefinitionCannotBeMarked(SQLiteStorageRuntime runtime) {
        DefinitionKey activeKey = new DefinitionKey("still_active");
        LoreDefinitionId activeId = createDefinition(runtime, activeKey, 5_000L);
        SQLiteDeletedDefinitionMarkerRepository repository =
                new SQLiteDeletedDefinitionMarkerRepository(runtime);
        assertThrows(CompletionException.class, () -> repository.create(
                        new DeletedDefinitionMarker(activeId, activeKey, 6_000L))
                .toCompletableFuture().join());
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
            MarkerMutation mutation,
            String lookupKey,
            LoreDefinitionId definitionId) {
        runtime.execute(connection -> {
                    // The SQL is selected from a closed test-only mutation enum.
                    try (PreparedStatement statement = connection.prepareStatement(mutation.sql())) { // nosemgrep
                        statement.setString(1, lookupKey);
                        statement.setString(2, definitionId.value().toString());
                        statement.executeUpdate();
                    }
                    return null;
                })
                .toCompletableFuture()
                .join();
    }
    private enum MarkerMutation {
        UPDATE_KEY("UPDATE deleted_definition_markers SET lookup_key = ? WHERE definition_id = ?"),
        DELETE_MARKER("DELETE FROM deleted_definition_markers WHERE lookup_key = ? AND definition_id = ?");

        private final String sql;

        MarkerMutation(String sql) {
            this.sql = sql;
        }

        String sql() {
            return sql;
        }
    }

    private record MarkerScenario(
            LoreDefinitionId definitionId, DeletedDefinitionMarker marker) {
    }

    private record MarkerHistory(
            DefinitionKey repeatedKey,
            DeletedDefinitionMarker first,
            DeletedDefinitionMarker other,
            DeletedDefinitionMarker second) {
    }

}
