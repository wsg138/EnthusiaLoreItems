package net.enthusia.loreitems.sqlite;

import java.sql.Connection;

final class SQLiteTransactions {
    private SQLiteTransactions() {
    }

    static <T> T inTransaction(Connection connection, Work<T> work) throws Exception {
        boolean ownsTransaction = connection.getAutoCommit();
        if (!ownsTransaction) {
            return work.execute(connection);
        }

        connection.setAutoCommit(false);
        try {
            T result = work.execute(connection);
            connection.commit();
            return result;
        } catch (Exception exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @FunctionalInterface
    interface Work<T> {
        T execute(Connection connection) throws Exception;
    }
}
