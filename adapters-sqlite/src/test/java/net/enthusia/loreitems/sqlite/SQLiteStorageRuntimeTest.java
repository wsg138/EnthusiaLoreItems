package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
