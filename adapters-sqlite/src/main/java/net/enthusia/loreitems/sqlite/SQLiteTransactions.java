package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

final class SQLiteTransactions {
    private SQLiteTransactions() {
    }

    static <T> T inTransaction(Connection connection, Work<T> work) throws Exception {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(work, "work");
        boolean ownsTransaction = connection.getAutoCommit();
        if (!ownsTransaction) {
            return work.execute(connection);
        }

        connection.setAutoCommit(false);
        try {
            T result = work.execute(connection);
            connection.commit();
            restoreAutoCommitAfterCommit(connection);
            return result;
        } catch (Exception exception) {
            rollback(connection, exception);
            restoreAutoCommitAfterFailure(connection, exception);
            throw exception;
        } catch (Error error) {
            rollback(connection, error);
            restoreAutoCommitAfterFailure(connection, error);
            throw error;
        }
    }

    /**
     * A successful commit is the durability boundary. The helper is used only with the
     * short-lived connections owned by SQLiteStorageRuntime, so a cleanup failure after commit
     * must not be reported as a transaction failure and trigger an unsafe retry of durable work.
     */
    private static void restoreAutoCommitAfterCommit(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // The owning runtime closes this connection immediately after the operation returns.
        }
    }

    private static void restoreAutoCommitAfterFailure(
            Connection connection, Throwable failure) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException restoreFailure) {
            failure.addSuppressed(restoreFailure);
        }
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    @FunctionalInterface
    interface Work<T> {
        T execute(Connection connection) throws Exception;
    }
}
