package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Statement;
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

class SQLiteDeletedDefinitionMarkerUnitOfWorkTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rollsBackSoftDeleteAndAuditWhenMarkerPersistenceFails() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("rollback.db"));
        try {
            DefinitionKey lookupKey = new DefinitionKey("rollback_marker");
            LoreDefinitionId definitionId = createDefinition(runtime, lookupKey);
            installMarkerRejection(runtime);
            assertUnitOfWorkFails(runtime, definitionId, lookupKey);
            assertNothingCommitted(runtime, definitionId);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static void assertUnitOfWorkFails(
            SQLiteStorageRuntime runtime,
            LoreDefinitionId definitionId,
            DefinitionKey lookupKey) {
        assertThrows(CompletionException.class, () -> new SQLiteUnitOfWork(runtime)
                .execute(context -> {
                    boolean deleted = context.definitions().markDeleted(
                            definitionId, new TemplateRevision(1), Instant.ofEpochMilli(2_000L));
                    if (!deleted) {
                        throw new IllegalStateException("Expected definition deletion to succeed");
                    }
                    context.audit().append(AuditEventRecord.pending(
                            "lore_definition", definitionId.value().toString(),
                            "definition_deleted", "system", null,
                            "{\"reason\":\"forced-marker-failure\"}", 2_000L));
                    context.deletedDefinitionMarkers().create(
                            new DeletedDefinitionMarker(definitionId, lookupKey, 2_000L));
                    return null;
                })
                .toCompletableFuture().join());
    }

    private static void assertNothingCommitted(
            SQLiteStorageRuntime runtime, LoreDefinitionId definitionId) {
        assertTrue(new SQLiteDefinitionRepository(runtime).findById(definitionId)
                .toCompletableFuture().join().orElseThrow().active());
        assertTrue(new SQLiteDeletedDefinitionMarkerRepository(runtime)
                .findByDefinitionId(definitionId).toCompletableFuture().join().isEmpty());
        assertTrue(new SQLiteAuditRepository(runtime).listByAggregate(
                        "lore_definition", definitionId.value().toString(), PageRequest.first(10))
                .toCompletableFuture().join().items().isEmpty());
    }

    private static LoreDefinitionId createDefinition(
            SQLiteStorageRuntime runtime, DefinitionKey key) {
        LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
        LoreDefinition definition = new LoreDefinition(
                definitionId,
                key,
                "Deleted marker rollback test",
                new TemplateRevision(1),
                1_000L,
                null);
        LoreDefinitionRevision revision = new LoreDefinitionRevision(
                definitionId,
                new TemplateRevision(1),
                1,
                new byte[] {1},
                1_000L);
        new SQLiteDefinitionRepository(runtime)
                .create(definition, revision)
                .toCompletableFuture()
                .join();
        return definitionId;
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

    private static void installMarkerRejection(SQLiteStorageRuntime runtime) {
        runtime.execute(connection -> {
                    try (Statement statement = connection.createStatement()) {
                        // Static test-only trigger used to force transactional rollback.
                        statement.execute(
                                "CREATE TRIGGER reject_deleted_marker "
                                        + "BEFORE INSERT ON deleted_definition_markers "
                                        + "BEGIN SELECT RAISE(ABORT, 'forced marker failure'); END");
                    }
                    return null;
                })
                .toCompletableFuture()
                .join();
    }
}
