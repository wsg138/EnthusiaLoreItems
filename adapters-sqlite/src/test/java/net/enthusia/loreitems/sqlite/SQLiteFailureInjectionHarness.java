package net.enthusia.loreitems.sqlite;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.StorageState;

final class SQLiteFailureInjectionHarness implements AutoCloseable {
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    enum FailurePoint {
        BEFORE_INSTANCE_INSERT(
                "CREATE TRIGGER wp04_fail_before_instance "
                        + "BEFORE INSERT ON lore_instances "
                        + "BEGIN SELECT RAISE(ABORT, 'wp04 before intent commit'); END",
                "DROP TRIGGER IF EXISTS wp04_fail_before_instance"),
        BEFORE_AUDIT_INSERT(
                "CREATE TRIGGER wp04_fail_before_audit "
                        + "BEFORE INSERT ON audit_events "
                        + "BEGIN SELECT RAISE(ABORT, 'wp04 during verification commit'); END",
                "DROP TRIGGER IF EXISTS wp04_fail_before_audit");

        private final String installStatement;
        private final String removeStatement;

        FailurePoint(String installStatement, String removeStatement) {
            this.installStatement = installStatement;
            this.removeStatement = removeStatement;
        }

        String sqlToInstall() {
            return installStatement;
        }

        String sqlToRemove() {
            return removeStatement;
        }
    }

    private final Path database;
    private SQLiteStorageRuntime activeRuntime;
    private boolean closed;

    SQLiteFailureInjectionHarness(Path database) {
        this.database = Objects.requireNonNull(database, "database");
        activeRuntime = start(database);
    }

    SQLiteStorageRuntime runtime() {
        if (closed) {
            throw new IllegalStateException("Failure-injection runtime is closed");
        }
        return activeRuntime;
    }

    void arm(FailurePoint point) {
        Objects.requireNonNull(point, "point");
        executeTriggerSql(point.sqlToInstall());
    }

    void disarm(FailurePoint point) {
        Objects.requireNonNull(point, "point");
        executeTriggerSql(point.sqlToRemove());
    }

    void restart() {
        ensureOpen();
        activeRuntime.close(CLOSE_TIMEOUT);
        activeRuntime = start(database);
    }

    private void executeTriggerSql(String sql) {
        runtime().execute(connection -> {
                    try (var statement = connection.createStatement()) {
                        // SQL comes only from the closed FailurePoint enum above.
                        statement.execute(sql); // nosemgrep
                    }
                    return null;
                })
                .toCompletableFuture()
                .join();
    }

    private static SQLiteStorageRuntime start(Path database) {
        MetricsPort metrics = MetricsPort.noOp();
        SQLiteStorageRuntime started = new SQLiteStorageRuntime(
                new SQLiteConnectionFactory(database, 5_000),
                new MigrationRunner(),
                new BoundedDatabaseExecutor("wp04-failure-matrix", 32, metrics),
                metrics);
        var startup = started.start().toCompletableFuture().join();
        if (startup.state() != StorageState.READ_WRITE) {
            started.close(CLOSE_TIMEOUT);
            throw new IllegalStateException(
                    "Failure-injection database did not start read-write: " + startup.state());
        }
        return started;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Failure-injection runtime is closed");
        }
    }

    @Override
    public void close() {
        if (!closed) {
            activeRuntime.close(CLOSE_TIMEOUT);
            closed = true;
        }
    }
}
