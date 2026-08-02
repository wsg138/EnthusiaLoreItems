package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteAuditRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void appendsImmutableIdsAndPagesAggregateHistoryNewestFirst() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("audit.db"));
        try {
            SQLiteAuditRepository repository = new SQLiteAuditRepository(runtime);
            AuditEventRecord first = repository.append(AuditEventRecord.pending(
                            "MUTATION",
                            "mutation-1",
                            "QUEUED",
                            "PLAYER",
                            "player-1",
                            "{\"reason\":\"edit\"}",
                            1_000L))
                    .toCompletableFuture()
                    .join();
            AuditEventRecord second = repository.append(AuditEventRecord.pending(
                            "MUTATION",
                            "mutation-1",
                            "CLAIMED",
                            "SYSTEM",
                            null,
                            "{\"worker\":\"worker-a\"}",
                            2_000L))
                    .toCompletableFuture()
                    .join();
            repository.append(AuditEventRecord.pending(
                            "DELIVERY",
                            "delivery-1",
                            "QUEUED",
                            "SYSTEM",
                            null,
                            "{}",
                            3_000L))
                    .toCompletableFuture()
                    .join();

            Page<AuditEventRecord> firstPage = repository
                    .listByAggregate("MUTATION", "mutation-1", PageRequest.first(1))
                    .toCompletableFuture()
                    .join();
            Page<AuditEventRecord> secondPage = repository
                    .listByAggregate("MUTATION", "mutation-1", new PageRequest(1, 1))
                    .toCompletableFuture()
                    .join();

            assertTrue(second.auditId() > first.auditId());
            assertEquals(1, firstPage.items().size());
            assertTrue(firstPage.hasMore());
            assertEquals("CLAIMED", firstPage.items().getFirst().eventType());
            assertEquals(1, secondPage.items().size());
            assertEquals("QUEUED", secondPage.items().getFirst().eventType());
        } finally {
            runtime.close(Duration.ofSeconds(5));
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
}
