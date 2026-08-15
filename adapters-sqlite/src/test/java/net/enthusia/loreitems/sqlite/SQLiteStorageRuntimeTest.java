package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.StorageState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteStorageRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void entersDegradedModeWhenDatabaseCannotOpen() throws Exception {
        Path directoryUsedAsDatabase = temporaryDirectory.resolve("not-a-database-file");
        Files.createDirectory(directoryUsedAsDatabase);
        MetricsPort metrics = MetricsPort.noOp();
        SQLiteStorageRuntime runtime = new SQLiteStorageRuntime(
                new SQLiteConnectionFactory(directoryUsedAsDatabase, 5_000),
                new MigrationRunner(),
                new BoundedDatabaseExecutor("degraded-test", 16, metrics),
                metrics);
        try {
            SQLiteStorageRuntime.StartupResult result =
                    runtime.start().toCompletableFuture().join();

            assertEquals(StorageState.DEGRADED_READ_ONLY, result.state());
            assertThrows(CompletionException.class, () -> runtime
                    .execute(connection -> 1)
                    .toCompletableFuture()
                    .join());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void negativeShutdownTimeoutDoesNotMutateRuntimeState() {
        MetricsPort metrics = MetricsPort.noOp();
        SQLiteStorageRuntime runtime = runtime("negative-timeout", metrics);
        try {
            assertEquals(
                    StorageState.READ_WRITE,
                    runtime.start().toCompletableFuture().join().state());

            assertThrows(
                    IllegalArgumentException.class,
                    () -> runtime.close(Duration.ofMillis(-1)));

            assertEquals(StorageState.READ_WRITE, runtime.state());
            assertEquals(1, runtime.execute(connection -> 1).toCompletableFuture().join());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void closedRuntimeCannotTransitionBackToStarting() {
        MetricsPort metrics = MetricsPort.noOp();
        SQLiteStorageRuntime runtime = runtime("closed-runtime", metrics);
        assertEquals(
                StorageState.READ_WRITE,
                runtime.start().toCompletableFuture().join().state());
        assertTrue(runtime.close(Duration.ofSeconds(5)));
        assertEquals(StorageState.STOPPED, runtime.state());

        SQLiteStorageRuntime.StartupResult restart =
                runtime.start().toCompletableFuture().join();

        assertEquals(StorageState.STOPPED, restart.state());
        assertEquals(StorageState.STOPPED, runtime.state());
        assertThrows(CompletionException.class, () -> runtime
                .execute(connection -> 1)
                .toCompletableFuture()
                .join());
    }

    private SQLiteStorageRuntime runtime(String threadName, MetricsPort metrics) {
        return new SQLiteStorageRuntime(
                new SQLiteConnectionFactory(temporaryDirectory.resolve(threadName + ".db"), 5_000),
                new MigrationRunner(),
                new BoundedDatabaseExecutor(threadName, 16, metrics),
                metrics);
    }
}
