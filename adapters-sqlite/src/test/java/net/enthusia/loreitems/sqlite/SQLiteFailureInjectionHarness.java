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

        private final String installSql;
        private final String removeSql;

        FailurePoint(String installSql, String removeSql) {
            this.installSql = installSql;
            this.removeSql = removeSql;
        }

        String installSql() {
            return installSql;
        }

        String removeSql() {
            return removeSql;
        }
    }

    private final Path database;
    private SQLiteStorageRuntime runtime;

    SQLiteFailureInjectionHarness(Path database) {
        this.database = Objects.requireNonNull(database, "database");
        runtime = start(database);
    }

    SQLiteStorageRuntime runtime() {
        SQLiteStorageRuntime active = runtime;
        if (active == null) {
            throw new IllegalStateException("Failure-injection runtime is closed");
        }
        return active;
    }

    void arm(FailurePoint point) {
        Objects.requireNonNull(point, "point");
        executeTriggerSql(point.installSql());
    }

    void disarm(FailurePoint point) {
        Objects.requireNonNull(point, "point");
        executeTriggerSql(point.removeSql());
    }

    void restart() {
        closeRuntime();
        runtime = start(database);
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

    private void closeRuntime() {
        SQLiteStorageRuntime active = runtime;
        runtime = null;
        if (active != null) {
            active.close(CLOSE_TIMEOUT);
        }
    }

    @Override
    public void close() {
        closeRuntime();
    }
}
