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
    private static final String MUTATION_AGGREGATE = "MUTATION";
    private static final String MUTATION_ID = "mutation-1";
    @TempDir
    Path temporaryDirectory;

    @Test
    void appendsImmutableIdsAndPagesAggregateHistoryNewestFirst() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("audit.db"));
        try {
            SQLiteAuditRepository repository = new SQLiteAuditRepository(runtime);
            AppendedAuditEvents events = appendHistory(repository);
            assertPagedHistory(repository, events);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static AppendedAuditEvents appendHistory(SQLiteAuditRepository repository) {
        AuditEventRecord first = repository.append(AuditEventRecord.pending(
                        MUTATION_AGGREGATE, MUTATION_ID, "QUEUED", "PLAYER", "player-1",
                        "{\"reason\":\"edit\"}", 1_000L))
                .toCompletableFuture().join();
        AuditEventRecord second = repository.append(AuditEventRecord.pending(
                        MUTATION_AGGREGATE, MUTATION_ID, "CLAIMED", "SYSTEM", null,
                        "{\"worker\":\"worker-a\"}", 2_000L))
                .toCompletableFuture().join();
        repository.append(AuditEventRecord.pending(
                        "DELIVERY", "delivery-1", "QUEUED", "SYSTEM", null, "{}", 3_000L))
                .toCompletableFuture().join();
        return new AppendedAuditEvents(first, second);
    }

    private static void assertPagedHistory(
            SQLiteAuditRepository repository, AppendedAuditEvents events) {
        Page<AuditEventRecord> firstPage = repository
                .listByAggregate(MUTATION_AGGREGATE, MUTATION_ID, PageRequest.first(1))
                .toCompletableFuture().join();
        Page<AuditEventRecord> secondPage = repository
                .listByAggregate(MUTATION_AGGREGATE, MUTATION_ID, new PageRequest(1, 1))
                .toCompletableFuture().join();

        assertTrue(events.second().auditId() > events.first().auditId());
        assertEquals(1, firstPage.items().size());
        assertTrue(firstPage.hasMore());
        assertEquals("CLAIMED", firstPage.items().getFirst().eventType());
        assertEquals(1, secondPage.items().size());
        assertEquals("QUEUED", secondPage.items().getFirst().eventType());
    }

    private record AppendedAuditEvents(AuditEventRecord first, AuditEventRecord second) {
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
