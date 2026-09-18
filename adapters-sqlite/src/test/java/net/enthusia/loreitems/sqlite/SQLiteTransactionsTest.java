package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SQLiteTransactionsTest {
    @Test
    void successfulCommitIsNotReportedAsFailureWhenPostCommitCleanupFails() throws Exception {
        AtomicBoolean committed = new AtomicBoolean();
        AtomicInteger rollbacks = new AtomicInteger();
        try (Connection connection = connectionThatRejectsPostCommitAutoCommitRestore(
                committed, rollbacks)) {
            String result = SQLiteTransactions.inTransaction(connection, ignored -> "committed");

            assertEquals("committed", result);
            assertTrue(committed.get());
            assertEquals(0, rollbacks.get());
        }
    }

    private static Connection connectionThatRejectsPostCommitAutoCommitRestore(
            AtomicBoolean committed,
            AtomicInteger rollbacks) {
        InvocationHandler handler = new PostCommitCleanupFailureHandler(committed, rollbacks);
        return (Connection) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Connection.class},
                handler);
    }

    private static final class PostCommitCleanupFailureHandler implements InvocationHandler {
        private final AtomicBoolean committed;
        private final AtomicInteger rollbacks;

        private PostCommitCleanupFailureHandler(
                AtomicBoolean committed,
                AtomicInteger rollbacks) {
            this.committed = committed;
            this.rollbacks = rollbacks;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            return switch (method.getName()) {
                case "getAutoCommit" -> true;
                case "setAutoCommit" -> setAutoCommit((boolean) arguments[0]);
                case "commit" -> commit();
                case "rollback" -> rollback();
                case "close" -> null;
                default -> handleConnectionObjectMethod(proxy, method, arguments);
            };
        }

        private Object setAutoCommit(boolean enabled) throws SQLException {
            if (enabled && committed.get()) {
                throw new SQLException("simulated post-commit cleanup failure");
            }
            return null;
        }

        private Object commit() {
            committed.set(true);
            return null;
        }

        private Object rollback() {
            rollbacks.incrementAndGet();
            return null;
        }

        @SuppressWarnings("PMD.CompareObjectsWithEquals")
        private static Object handleConnectionObjectMethod(
                Object proxy,
                Method method,
                Object[] arguments) throws SQLException {
            return switch (method.getName()) {
                case "isWrapperFor" -> false;
                case "unwrap" -> throw new SQLException("not a wrapper");
                case "toString" -> "post-commit-cleanup-test-connection";
                case "hashCode" -> System.identityHashCode(proxy);
                // A dynamic proxy's equals implementation intentionally needs reference identity;
                // calling equals() here would recursively invoke this handler.
                case "equals" -> proxy == arguments[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }
}
