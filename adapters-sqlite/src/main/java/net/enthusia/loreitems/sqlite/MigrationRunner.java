package net.enthusia.loreitems.sqlite;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class MigrationRunner {
    private static final int FOUNDATION_VERSION = 1;
    private static final String FOUNDATION_RESOURCE = "db/migration/V1__foundation.sql";

    public void migrate(Connection connection) throws SQLException {
        ensureHistoryTable(connection);
        if (isApplied(connection, FOUNDATION_VERSION)) {
            return;
        }

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            executeScript(connection, readResource(FOUNDATION_RESOURCE));
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO schema_history(version, description, applied_at) VALUES (?, ?, ?)")) {
                insert.setInt(1, FOUNDATION_VERSION);
                insert.setString(2, "foundation");
                insert.setLong(3, System.currentTimeMillis());
                insert.executeUpdate();
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static void ensureHistoryTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS schema_history ("
                            + "version INTEGER PRIMARY KEY,"
                            + "description TEXT NOT NULL,"
                            + "applied_at INTEGER NOT NULL)");
        }
    }

    private static boolean isApplied(Connection connection, int version) throws SQLException {
        try (PreparedStatement query =
                     connection.prepareStatement("SELECT 1 FROM schema_history WHERE version = ?")) {
            query.setInt(1, version);
            try (ResultSet resultSet = query.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static String readResource(String resource) {
        try (InputStream stream = MigrationRunner.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing migration resource: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read migration resource: " + resource, exception);
        }
    }

    private static void executeScript(Connection connection, String script) throws SQLException {
        for (String statementText : script.split(";")) {
            String trimmed = statementText.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute(trimmed);
            }
        }
    }
}
