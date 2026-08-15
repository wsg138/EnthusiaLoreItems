package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        Connection connection = connectionThatRejectsPostCommitAutoCommitRestore(
                committed, rollbacks);

        String result = SQLiteTransactions.inTransaction(connection, ignored -> "committed");

        assertEquals("committed", result);
        assertTrue(committed.get());
        assertEquals(0, rollbacks.get());
    }

    private static Connection connectionThatRejectsPostCommitAutoCommitRestore(
            AtomicBoolean committed,
            AtomicInteger rollbacks) {
        return (Connection) Proxy.newProxyInstance(
                SQLiteTransactionsTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getAutoCommit" -> true;
                    case "setAutoCommit" -> {
                        boolean enabled = (boolean) arguments[0];
                        if (enabled && committed.get()) {
                            throw new SQLException("simulated post-commit cleanup failure");
                        }
                        yield null;
                    }
                    case "commit" -> {
                        committed.set(true);
                        yield null;
                    }
                    case "rollback" -> {
                        rollbacks.incrementAndGet();
                        yield null;
                    }
                    case "isWrapperFor" -> false;
                    case "unwrap" -> throw new SQLException("not a wrapper");
                    case "toString" -> "post-commit-cleanup-test-connection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
