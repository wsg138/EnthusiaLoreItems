package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.StorageState;

public final class SQLiteStorageRuntime {
    private final SQLiteConnectionFactory connectionFactory;
    private final MigrationRunner migrationRunner;
    private final BoundedDatabaseExecutor executor;
    private final MetricsPort metrics;
    private final AtomicReference<StorageState> state = new AtomicReference<>(StorageState.STOPPED);

    public SQLiteStorageRuntime(
            SQLiteConnectionFactory connectionFactory,
            MigrationRunner migrationRunner,
            BoundedDatabaseExecutor executor,
            MetricsPort metrics) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.migrationRunner = Objects.requireNonNull(migrationRunner, "migrationRunner");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public CompletionStage<StartupResult> start() {
        if (!state.compareAndSet(StorageState.STOPPED, StorageState.STARTING)) {
            return CompletableFuture.completedFuture(
                    new StartupResult(state.get(), "Storage runtime was already started."));
        }
        return executor.submit(() -> {
            try (Connection connection = connectionFactory.open()) {
                migrationRunner.migrate(connection);
                state.set(StorageState.READ_WRITE);
                metrics.setGauge("storage.writable", 1L);
                return new StartupResult(StorageState.READ_WRITE, "SQLite storage initialized.");
            } catch (Exception exception) {
                state.set(StorageState.DEGRADED_READ_ONLY);
                metrics.setGauge("storage.writable", 0L);
                metrics.increment("storage.startup.failure");
                return new StartupResult(
                        StorageState.DEGRADED_READ_ONLY,
                        exception.getClass().getSimpleName() + ": " + safeMessage(exception));
            }
        });
    }

    public <T> CompletionStage<T> execute(SqlWork<T> work) {
        Objects.requireNonNull(work, "work");
        if (state.get() != StorageState.READ_WRITE) {
            return CompletableFuture.failedFuture(
                    new StorageUnavailableException("SQLite storage is not writable: " + state.get()));
        }
        return executor.submit(() -> {
            try (Connection connection = connectionFactory.open()) {
                return work.execute(connection);
            }
        });
    }

    public StorageState state() {
        return state.get();
    }

    public int queueDepth() {
        return executor.queueDepth();
    }

    public boolean close(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        StorageState previous = state.getAndSet(StorageState.STOPPING);
        if (previous == StorageState.STOPPED) {
            return true;
        }
        boolean drained = executor.shutdown(timeout);
        state.set(StorageState.STOPPED);
        metrics.setGauge("storage.writable", 0L);
        return drained;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "no detail" : message;
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T execute(Connection connection) throws Exception;
    }

    public record StartupResult(StorageState state, String detail) {
        public StartupResult {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(detail, "detail");
        }
    }

    public static final class StorageUnavailableException extends IllegalStateException {
        public StorageUnavailableException(String message) {
            super(message);
        }
    }
}
