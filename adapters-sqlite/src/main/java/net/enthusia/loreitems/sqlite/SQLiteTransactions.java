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
            restoreAutoCommit(connection);
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

    private static void restoreAutoCommit(Connection connection) throws SQLException {
        connection.setAutoCommit(true);
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
