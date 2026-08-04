package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreDefinitionRevision;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteUnitOfWorkTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void commitsAuthoritativeMutationAndAuditEventTogether() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("commit.db"));
        try {
            LoreDefinitionId definitionId = createDefinition(runtime, "commit");
            SQLiteUnitOfWork unitOfWork = new SQLiteUnitOfWork(runtime);
            AuditEventRecord pendingAudit = deletionAudit(definitionId, 2_000L);

            AuditEventRecord persistedAudit = unitOfWork.execute(context -> {
                        boolean deleted = context.definitions().markDeleted(
                                definitionId,
                                new TemplateRevision(1),
                                Instant.ofEpochMilli(2_000L));
                        if (!deleted) {
                            throw new IllegalStateException("Expected definition deletion to succeed");
                        }
                        return context.audit().append(pendingAudit);
                    })
                    .toCompletableFuture()
                    .join();

            assertTrue(persistedAudit.auditId() > 0L);
            assertFalse(new SQLiteDefinitionRepository(runtime)
                    .findById(definitionId)
                    .toCompletableFuture()
                    .join()
                    .orElseThrow()
                    .active());
            var events = new SQLiteAuditRepository(runtime)
                    .listByAggregate(
                            "lore_definition",
                            definitionId.value().toString(),
                            PageRequest.first(10))
                    .toCompletableFuture()
                    .join();
            assertEquals(1, events.items().size());
            assertEquals(persistedAudit.auditId(), events.items().getFirst().auditId());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void rollsBackAuthoritativeMutationWhenAuditAppendFails() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("rollback.db"));
        try {
            LoreDefinitionId definitionId = createDefinition(runtime, "rollback");
            execute(runtime, "CREATE TRIGGER reject_unit_of_work_audit "
                    + "BEFORE INSERT ON audit_events "
                    + "BEGIN SELECT RAISE(ABORT, 'forced audit failure'); END");
            SQLiteUnitOfWork unitOfWork = new SQLiteUnitOfWork(runtime);

            assertThrows(
                    CompletionException.class,
                    () -> unitOfWork.execute(context -> {
                                boolean deleted = context.definitions().markDeleted(
                                        definitionId,
                                        new TemplateRevision(1),
                                        Instant.ofEpochMilli(2_000L));
                                if (!deleted) {
                                    throw new IllegalStateException(
                                            "Expected definition deletion to succeed");
                                }
                                context.audit().append(deletionAudit(definitionId, 2_000L));
                                return null;
                            })
                            .toCompletableFuture()
                            .join());

            assertTrue(new SQLiteDefinitionRepository(runtime)
                    .findById(definitionId)
                    .toCompletableFuture()
                    .join()
                    .orElseThrow()
                    .active());
            assertTrue(new SQLiteAuditRepository(runtime)
                    .listByAggregate(
                            "lore_definition",
                            definitionId.value().toString(),
                            PageRequest.first(10))
                    .toCompletableFuture()
                    .join()
                    .items()
                    .isEmpty());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void createsDefinitionRevisionAndAuditAtomically() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("create-commit.db"));
        try {
            LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
            LoreDefinition definition = definition(definitionId, "created", 3_000L);
            LoreDefinitionRevision revision = revision(definitionId, 3_000L);
            SQLiteUnitOfWork unitOfWork = new SQLiteUnitOfWork(runtime);

            boolean created = unitOfWork.execute(context -> {
                        if (!context.definitions().create(definition, revision)) {
                            return false;
                        }
                        context.audit().append(creationAudit(definitionId, 3_000L));
                        return true;
                    })
                    .toCompletableFuture()
                    .join();

            assertTrue(created);
            assertTrue(new SQLiteDefinitionRepository(runtime)
                    .findActiveByKey(new DefinitionKey("created"))
                    .toCompletableFuture()
                    .join()
                    .isPresent());
            assertEquals(1, new SQLiteAuditRepository(runtime)
                    .listByAggregate(
                            "lore_definition",
                            definitionId.value().toString(),
                            PageRequest.first(10))
                    .toCompletableFuture()
                    .join()
                    .items()
                    .size());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void rollsBackDefinitionCreationWhenAuditAppendFails() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("create-rollback.db"));
        try {
            execute(runtime, "CREATE TRIGGER reject_creation_audit "
                    + "BEFORE INSERT ON audit_events "
                    + "BEGIN SELECT RAISE(ABORT, 'forced creation audit failure'); END");
            LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
            LoreDefinition definition = definition(definitionId, "create_rollback", 4_000L);
            LoreDefinitionRevision revision = revision(definitionId, 4_000L);
            SQLiteUnitOfWork unitOfWork = new SQLiteUnitOfWork(runtime);

            assertThrows(
                    CompletionException.class,
                    () -> unitOfWork.execute(context -> {
                                if (!context.definitions().create(definition, revision)) {
                                    throw new IllegalStateException("Expected definition creation");
                                }
                                context.audit().append(creationAudit(definitionId, 4_000L));
                                return null;
                            })
                            .toCompletableFuture()
                            .join());

            assertTrue(new SQLiteDefinitionRepository(runtime)
                    .findById(definitionId)
                    .toCompletableFuture()
                    .join()
                    .isEmpty());
            assertTrue(new SQLiteAuditRepository(runtime)
                    .listByAggregate(
                            "lore_definition",
                            definitionId.value().toString(),
                            PageRequest.first(10))
                    .toCompletableFuture()
                    .join()
                    .items()
                    .isEmpty());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static LoreDefinitionId createDefinition(
            SQLiteStorageRuntime runtime, String key) {
        LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
        LoreDefinition definition = new LoreDefinition(
                definitionId,
                new DefinitionKey(key),
                "Unit of Work Test",
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

    private static LoreDefinition definition(
            LoreDefinitionId definitionId, String key, long createdAt) {
        return new LoreDefinition(
                definitionId,
                new DefinitionKey(key),
                "Unit of Work Test",
                new TemplateRevision(1),
                createdAt,
                null);
    }

    private static LoreDefinitionRevision revision(
            LoreDefinitionId definitionId, long createdAt) {
        return new LoreDefinitionRevision(
                definitionId,
                new TemplateRevision(1),
                1,
                new byte[] {1},
                createdAt);
    }

    private static AuditEventRecord creationAudit(
            LoreDefinitionId definitionId, long occurredAt) {
        return AuditEventRecord.pending(
                "lore_definition",
                definitionId.value().toString(),
                "definition_created",
                "player",
                UUID.randomUUID().toString(),
                "{\"key\":\"unit_test\",\"revision\":1}",
                occurredAt);
    }

    private static AuditEventRecord deletionAudit(
            LoreDefinitionId definitionId, long occurredAt) {
        return AuditEventRecord.pending(
                "lore_definition",
                definitionId.value().toString(),
                "definition_deleted",
                "system",
                null,
                "{\"reason\":\"unit-of-work-test\"}",
                occurredAt);
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

    private static void execute(SQLiteStorageRuntime runtime, String sql) {
        runtime.execute(connection -> {
                    try (Statement statement = connection.createStatement()) {
                        // SQL is a closed test-only fault-injection fixture.
                        statement.execute(sql); // nosemgrep
                    }
                    return null;
                })
                .toCompletableFuture()
                .join();
    }
}
