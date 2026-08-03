package net.enthusia.loreitems.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

public final class SQLiteConnectionFactory {
    private final String jdbcUrl;
    private final int busyTimeoutMillis;

    public SQLiteConnectionFactory(Path databasePath, int busyTimeoutMillis) {
        Objects.requireNonNull(databasePath, "databasePath");
        if (busyTimeoutMillis < 100 || busyTimeoutMillis > 60_000) {
            throw new IllegalArgumentException("Busy timeout must be between 100 and 60000 ms");
        }
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
        this.busyTimeoutMillis = busyTimeoutMillis;
    }

    public Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        boolean configured = false;
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
            statement.execute("PRAGMA busy_timeout = " + busyTimeoutMillis);
            configured = true;
            return connection;
        } finally {
            if (!configured) {
                connection.close();
            }
        }
    }
}
