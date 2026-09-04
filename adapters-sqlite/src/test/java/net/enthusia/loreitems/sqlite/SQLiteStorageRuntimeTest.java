package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    void bootstrapConnectionCloseFailureCannotLeaveRuntimeWritable() {
        Path database = temporaryDirectory.resolve("close-failure.db");
        SQLiteConnectionFactory factory = new SQLiteConnectionFactory(database, 5_000);
        MetricsPort metrics = MetricsPort.noOp();
        SQLiteStorageRuntime runtime = new SQLiteStorageRuntime(
                () -> closeThenFail(factory.open()),
                new MigrationRunner(),
                new BoundedDatabaseExecutor("close-failure-test", 16, metrics),
                metrics);
        try {
            SQLiteStorageRuntime.StartupResult result =
                    runtime.start().toCompletableFuture().join();

            assertEquals(StorageState.DEGRADED_READ_ONLY, result.state());
            assertEquals(StorageState.DEGRADED_READ_ONLY, runtime.state());
            assertTrue(result.detail().contains("simulated bootstrap close failure"));
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

    @Test
    void forcedShutdownOfQueuedStartupCompletesWithTerminalResult() throws Exception {
        MetricsPort metrics = MetricsPort.noOp();
        BoundedDatabaseExecutor executor = new BoundedDatabaseExecutor("startup-race", 16, metrics);
        CountDownLatch blockerEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        executor.submit(() -> {
            blockerEntered.countDown();
            releaseBlocker.await();
            return null;
        });
        assertTrue(blockerEntered.await(5, TimeUnit.SECONDS));
        SQLiteStorageRuntime runtime = new SQLiteStorageRuntime(
                new SQLiteConnectionFactory(temporaryDirectory.resolve("startup-race.db"), 5_000),
                new MigrationRunner(),
                executor,
                metrics);

        var startup = runtime.start();
        assertTrue(!runtime.close(Duration.ZERO));
        releaseBlocker.countDown();

        SQLiteStorageRuntime.StartupResult result = startup.toCompletableFuture().join();
        assertEquals(StorageState.STOPPED, result.state());
        assertEquals(StorageState.STOPPED, runtime.state());
    }

    @Test
    void forcedShutdownExposesInterruptResistantRunningTaskUntilItExits() throws Exception {
        MetricsPort metrics = MetricsPort.noOp();
        SQLiteStorageRuntime runtime = runtime("surviving-task", metrics);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        assertEquals(
                StorageState.READ_WRITE,
                runtime.start().toCompletableFuture().join().state());
        var running = runtime.execute(connection -> {
            entered.countDown();
            boolean released = false;
            while (!released) {
                try {
                    released = release.await(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    Thread.interrupted();
                }
            }
            return 1;
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(runtime.close(Duration.ZERO));
            assertFalse(runtime.isTerminated());
        } finally {
            release.countDown();
        }
        assertEquals(1, running.toCompletableFuture().get(1, TimeUnit.SECONDS));
    }

    private static Connection closeThenFail(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> invoke(delegate, method, arguments));
    }

    private static Object invoke(
            Connection delegate,
            Method method,
            Object[] arguments) throws Throwable {
        try {
            Object result = method.invoke(delegate, arguments);
            if ("close".equals(method.getName())) {
                throw new SQLException("simulated bootstrap close failure");
            }
            return result;
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private SQLiteStorageRuntime runtime(String threadName, MetricsPort metrics) {
        return new SQLiteStorageRuntime(
                new SQLiteConnectionFactory(temporaryDirectory.resolve(threadName + ".db"), 5_000),
                new MigrationRunner(),
                new BoundedDatabaseExecutor(threadName, 16, metrics),
                metrics);
    }
}
