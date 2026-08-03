package net.enthusia.loreitems.sqlite;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class MigrationRunner {
    private static final int FOUNDATION_VERSION = 1;
    private static final String FOUNDATION_RESOURCE = "db/migration/V1__foundation.sql";
    private static final String CREATE_TRIGGER = "CREATE TRIGGER";

    public void migrate(Connection connection) throws SQLException {
        ensureHistoryTable(connection);
        if (isApplied(connection, FOUNDATION_VERSION)) {
            return;
        }

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            executeScript(connection, readResource(FOUNDATION_RESOURCE));
            insertHistory(connection);
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            rollbackAfterFailure(connection, exception);
            restoreAutoCommitAfterFailure(connection, previousAutoCommit, exception);
            throw exception;
        }
        connection.setAutoCommit(previousAutoCommit);
    }

    private static void insertHistory(Connection connection) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO schema_history(version, description, applied_at) VALUES (?, ?, ?)")) {
            insert.setInt(1, FOUNDATION_VERSION);
            insert.setString(2, "foundation");
            insert.setLong(3, System.currentTimeMillis());
            insert.executeUpdate();
        }
    }

    private static void rollbackAfterFailure(Connection connection, Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void restoreAutoCommitAfterFailure(
            Connection connection, boolean previousAutoCommit, Exception failure) {
        try {
            connection.setAutoCommit(previousAutoCommit);
        } catch (SQLException restoreFailure) {
            failure.addSuppressed(restoreFailure);
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
        try (InputStream stream = MigrationRunner.class.getResourceAsStream("/" + resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing migration resource: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read migration resource: " + resource, exception);
        }
    }

    private static void executeScript(Connection connection, String script) throws SQLException {
        for (String statementText : splitStatements(script)) {
            try (Statement statement = connection.createStatement()) {
                // SQL is loaded only from the versioned classpath migration resource.
                statement.execute(statementText); // nosemgrep
            }
        }
    }

    private static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean triggerStatement = false;

        for (String line : script.split("\\R", -1)) {
            String trimmed = line.strip();
            if (current.length() == 0 && trimmed.isEmpty()) {
                continue;
            }
            if (current.length() == 0) {
                triggerStatement = trimmed.regionMatches(
                        true, 0, CREATE_TRIGGER, 0, CREATE_TRIGGER.length());
            }
            current.append(line).append('\n');

            boolean complete = triggerStatement
                    ? trimmed.equalsIgnoreCase("END;")
                    : trimmed.endsWith(";");
            if (complete) {
                statements.add(current.toString().strip());
                current.setLength(0);
                triggerStatement = false;
            }
        }

        if (!current.toString().isBlank()) {
            throw new IllegalArgumentException("Migration contains an incomplete SQL statement");
        }
        return List.copyOf(statements);
    }
}
