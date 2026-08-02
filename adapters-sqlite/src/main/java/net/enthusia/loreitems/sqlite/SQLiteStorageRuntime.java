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
                    new StartupResult(state.get(), "Storage runtime was already started or stopped."));
        }
        return executor.submit(() -> {
            try (Connection connection = connectionFactory.open()) {
                migrationRunner.migrate(connection);
                if (!state.compareAndSet(StorageState.STARTING, StorageState.READ_WRITE)) {
                    return new StartupResult(state.get(), "Storage startup was superseded by shutdown.");
                }
                metrics.setGauge("storage.writable", 1L);
                return new StartupResult(StorageState.READ_WRITE, "SQLite storage initialized.");
            } catch (Exception exception) {
                if (state.compareAndSet(StorageState.STARTING, StorageState.DEGRADED_READ_ONLY)) {
                    metrics.setGauge("storage.writable", 0L);
                    metrics.increment("storage.startup.failure");
                    return new StartupResult(
                            StorageState.DEGRADED_READ_ONLY,
                            exception.getClass().getSimpleName() + ": " + safeMessage(exception));
                }
                return new StartupResult(state.get(), "Storage startup was superseded by shutdown.");
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
        state.set(StorageState.STOPPING);
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
        private static final long serialVersionUID = 1L;

        public StorageUnavailableException(String message) {
            super(message);
        }
    }
}
