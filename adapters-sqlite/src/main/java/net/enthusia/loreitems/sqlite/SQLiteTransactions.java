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
        Throwable failure = null;
        try {
            T result = work.execute(connection);
            connection.commit();
            return result;
        } catch (Exception exception) {
            failure = exception;
            rollback(connection, exception);
            throw exception;
        } catch (Error error) {
            failure = error;
            rollback(connection, error);
            throw error;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException restoreFailure) {
                if (failure != null) {
                    failure.addSuppressed(restoreFailure);
                } else {
                    throw restoreFailure;
                }
            }
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
