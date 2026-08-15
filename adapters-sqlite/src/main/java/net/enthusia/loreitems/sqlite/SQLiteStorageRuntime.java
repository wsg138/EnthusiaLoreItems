package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.StorageState;

public final class SQLiteStorageRuntime {
    private final SQLiteConnectionFactory connectionFactory;
    private final MigrationRunner migrationRunner;
    private final BoundedDatabaseExecutor executor;
    private final MetricsPort metrics;
    private final AtomicReference<StorageState> storageState = new AtomicReference<>(StorageState.STOPPED);
    private final AtomicBoolean closed = new AtomicBoolean();

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

    public synchronized CompletionStage<StartupResult> start() {
        if (closed.get()) {
            return CompletableFuture.completedFuture(
                    new StartupResult(StorageState.STOPPED, "Storage runtime is closed and cannot be restarted."));
        }
        if (!storageState.compareAndSet(StorageState.STOPPED, StorageState.STARTING)) {
            return CompletableFuture.completedFuture(
                    new StartupResult(storageState.get(), "Storage runtime was already started or stopped."));
        }
        if (closed.get()) {
            storageState.compareAndSet(StorageState.STARTING, StorageState.STOPPING);
            return CompletableFuture.completedFuture(
                    new StartupResult(storageState.get(), "Storage startup was superseded by shutdown."));
        }
        CompletionStage<StartupResult> startup = executor.submit(() -> {
            try (Connection connection = connectionFactory.open()) {
                migrationRunner.migrate(connection);
                if (!storageState.compareAndSet(StorageState.STARTING, StorageState.READ_WRITE)) {
                    return shutdownStartupResult();
                }
                metrics.setGauge("storage.writable", 1L);
                return new StartupResult(StorageState.READ_WRITE, "SQLite storage initialized.");
            } catch (Exception exception) {
                if (storageState.compareAndSet(StorageState.STARTING, StorageState.DEGRADED_READ_ONLY)) {
                    metrics.setGauge("storage.writable", 0L);
                    metrics.increment("storage.startup.failure");
                    return new StartupResult(
                            StorageState.DEGRADED_READ_ONLY,
                            exception.getClass().getSimpleName() + ": " + safeMessage(exception));
                }
                return shutdownStartupResult();
            }
        });
        return startup.handle((result, failure) -> {
            if (failure == null) {
                return result;
            }
            if (closed.get() || storageState.get() == StorageState.STOPPING
                    || storageState.get() == StorageState.STOPPED) {
                return shutdownStartupResult();
            }
            throw new CompletionException(unwrapCompletionFailure(failure));
        });
    }

    public <T> CompletionStage<T> execute(SqlWork<T> work) {
        Objects.requireNonNull(work, "work");
        if (storageState.get() != StorageState.READ_WRITE) {
            return CompletableFuture.failedFuture(
                    new StorageUnavailableException("SQLite storage is not writable: " + storageState.get()));
        }
        return executor.submit(() -> {
            try (Connection connection = connectionFactory.open()) {
                return work.execute(connection);
            }
        });
    }

    public StorageState state() {
        return storageState.get();
    }

    public int queueDepth() {
        return executor.queueDepth();
    }

    public MetricsPort metrics() {
        return metrics;
    }

    public synchronized boolean close(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        if (!closed.compareAndSet(false, true)) {
            return storageState.get() == StorageState.STOPPED;
        }
        storageState.set(StorageState.STOPPING);
        boolean drained = executor.shutdown(timeout);
        storageState.set(StorageState.STOPPED);
        metrics.setGauge("storage.writable", 0L);
        return drained;
    }

    private StartupResult shutdownStartupResult() {
        return new StartupResult(storageState.get(), "Storage startup was superseded by shutdown.");
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        return failure instanceof CompletionException completion && completion.getCause() != null
                ? completion.getCause()
                : failure;
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
