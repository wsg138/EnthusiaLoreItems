package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
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
import net.enthusia.loreitems.domain.LoreInstance;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.LoreInstanceLifecycle;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteInstanceRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void enforcesIdentityAndRevisionIntegrityWithBoundedRestartSafeReads() {
        Path database = temporaryDirectory.resolve("instances.db");
        LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
        LoreInstance first = instance(definitionId, 10L);
        LoreInstance second = instance(definitionId, 20L);
        LoreInstance third = instance(definitionId, 30L);

        SQLiteStorageRuntime firstRuntime = start(database);
        try {
            seedTwoRevisions(firstRuntime, definitionId);
            SQLiteInstanceRepository repository = new SQLiteInstanceRepository(firstRuntime);
            repository.create(first).toCompletableFuture().join();
            repository.create(second).toCompletableFuture().join();
            repository.create(third).toCompletableFuture().join();

            assertThrows(
                    CompletionException.class,
                    () -> repository.create(first).toCompletableFuture().join());
            LoreInstance invalidRevision = new LoreInstance(
                    new LoreInstanceId(UUID.randomUUID()),
                    definitionId,
                    new TemplateRevision(3),
                    new TemplateRevision(3),
                    LoreInstanceLifecycle.ACTIVE,
                    40L,
                    null);
            assertThrows(
                    CompletionException.class,
                    () -> repository.create(invalidRevision).toCompletableFuture().join());

            Page<LoreInstance> firstPage = repository
                    .listByDefinition(definitionId, PageRequest.first(2))
                    .toCompletableFuture()
                    .join();
            Page<LoreInstance> secondPage = repository
                    .listByDefinition(definitionId, new PageRequest(2, 2))
                    .toCompletableFuture()
                    .join();
            assertEquals(2, firstPage.items().size());
            assertTrue(firstPage.hasMore());
            assertEquals(1, secondPage.items().size());
            assertFalse(secondPage.hasMore());

            assertTrue(repository.compareAndSetRevisions(
                            first.id(),
                            new TemplateRevision(1),
                            new TemplateRevision(1),
                            new TemplateRevision(1),
                            new TemplateRevision(2))
                    .toCompletableFuture()
                    .join());
            assertFalse(repository.compareAndSetRevisions(
                            first.id(),
                            new TemplateRevision(1),
                            new TemplateRevision(1),
                            new TemplateRevision(1),
                            new TemplateRevision(2))
                    .toCompletableFuture()
                    .join());
            assertTrue(repository.compareAndSetRevisions(
                            first.id(),
                            new TemplateRevision(1),
                            new TemplateRevision(2),
                            new TemplateRevision(2),
                            new TemplateRevision(2))
                    .toCompletableFuture()
                    .join());
            assertTrue(repository.compareAndSetLifecycle(
                            first.id(),
                            LoreInstanceLifecycle.ACTIVE,
                            LoreInstanceLifecycle.VOID_DESTROYED,
                            Instant.ofEpochMilli(5_000L))
                    .toCompletableFuture()
                    .join());
        } finally {
            firstRuntime.close(Duration.ofSeconds(5));
        }

        SQLiteStorageRuntime secondRuntime = start(database);
        try {
            LoreInstance restored = new SQLiteInstanceRepository(secondRuntime)
                    .findById(first.id())
                    .toCompletableFuture()
                    .join()
                    .orElseThrow();
            assertEquals(new TemplateRevision(2), restored.appliedRevision());
            assertEquals(new TemplateRevision(2), restored.desiredRevision());
            assertEquals(LoreInstanceLifecycle.VOID_DESTROYED, restored.lifecycle());
            assertEquals(5_000L, restored.terminalAtEpochMillis());
        } finally {
            secondRuntime.close(Duration.ofSeconds(5));
        }
    }

    private static LoreInstance instance(LoreDefinitionId definitionId, long createdAt) {
        return new LoreInstance(
                new LoreInstanceId(UUID.randomUUID()),
                definitionId,
                new TemplateRevision(1),
                new TemplateRevision(1),
                LoreInstanceLifecycle.ACTIVE,
                createdAt,
                null);
    }

    private static void seedTwoRevisions(
            SQLiteStorageRuntime runtime, LoreDefinitionId definitionId) {
        SQLiteDefinitionRepository definitions = new SQLiteDefinitionRepository(runtime);
        definitions.create(
                        new LoreDefinition(
                                definitionId,
                                new DefinitionKey("instance-test"),
                                "Instance Test",
                                new TemplateRevision(1),
                                1L,
                                null),
                        new LoreDefinitionRevision(
                                definitionId,
                                new TemplateRevision(1),
                                1,
                                new byte[] {1},
                                1L))
                .toCompletableFuture()
                .join();
        assertTrue(definitions.appendRevision(
                        definitionId,
                        new TemplateRevision(1),
                        new LoreDefinitionRevision(
                                definitionId,
                                new TemplateRevision(2),
                                1,
                                new byte[] {2},
                                2L))
                .toCompletableFuture()
                .join());
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
}
