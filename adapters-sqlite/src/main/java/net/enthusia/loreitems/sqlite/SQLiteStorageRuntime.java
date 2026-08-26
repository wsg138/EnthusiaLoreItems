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
    private final ConnectionProvider connectionProvider;
    private final MigrationRunner migrationRunner;
    private final BoundedDatabaseExecutor executor;
    private final MetricsPort metrics;
    private final AtomicReference<StorageState> storageState = new AtomicReference<>(StorageState.STOPPED);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

    public SQLiteStorageRuntime(
            SQLiteConnectionFactory connectionFactory,
            MigrationRunner migrationRunner,
            BoundedDatabaseExecutor executor,
            MetricsPort metrics) {
        this(connectionProvider(connectionFactory), migrationRunner, executor, metrics);
    }

    SQLiteStorageRuntime(
            ConnectionProvider connectionProvider,
            MigrationRunner migrationRunner,
            BoundedDatabaseExecutor executor,
            MetricsPort metrics) {
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
        this.migrationRunner = Objects.requireNonNull(migrationRunner, "migrationRunner");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public CompletionStage<StartupResult> start() {
        CompletionStage<StartupResult> startup;
        synchronized (lifecycleLock) {
            StartupResult rejected = transitionToStarting();
            if (rejected != null) {
                return CompletableFuture.completedFuture(rejected);
            }
            // Submission is protected by the same lock as close(), so shutdown cannot begin
            // between admitting STARTING and handing the startup task to the executor.
            startup = executor.submit(this::initializeStorage);
        }
        return startup.handle(this::resolveStartupCompletion);
    }

    private StartupResult transitionToStarting() {
        if (closed.get()) {
            return new StartupResult(
                    StorageState.STOPPED,
                    "Storage runtime is closed and cannot be restarted.");
        }
        if (!storageState.compareAndSet(StorageState.STOPPED, StorageState.STARTING)) {
            return new StartupResult(
                    storageState.get(),
                    "Storage runtime was already started or stopped.");
        }
        return null;
    }

    private StartupResult initializeStorage() {
        try {
            try (Connection connection = connectionProvider.open()) {
                migrationRunner.migrate(connection);
            }
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
    }

    public <T> CompletionStage<T> execute(SqlWork<T> work) {
        Objects.requireNonNull(work, "work");
        if (storageState.get() != StorageState.READ_WRITE) {
            return CompletableFuture.failedFuture(
                    new StorageUnavailableException("SQLite storage is not writable: " + storageState.get()));
        }
        return executor.submit(() -> {
            try (Connection connection = connectionProvider.open()) {
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

    public boolean close(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return storageState.get() == StorageState.STOPPED;
            }
            storageState.set(StorageState.STOPPING);
            boolean drained = executor.shutdown(timeout);
            storageState.set(StorageState.STOPPED);
            metrics.setGauge("storage.writable", 0L);
            return drained;
        }
    }

    private static ConnectionProvider connectionProvider(SQLiteConnectionFactory connectionFactory) {
        Objects.requireNonNull(connectionFactory, "connectionFactory");
        return connectionFactory::open;
    }

    private static StartupResult shutdownStartupResult() {
        return new StartupResult(StorageState.STOPPED, "Storage startup was superseded by shutdown.");
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
    interface ConnectionProvider {
        Connection open() throws Exception;
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
