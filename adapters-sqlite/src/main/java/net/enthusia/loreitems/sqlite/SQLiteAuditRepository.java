package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.AuditRepository;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;

public final class SQLiteAuditRepository implements AuditRepository {
    private final SQLiteStorageRuntime storage;

    public SQLiteAuditRepository(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<AuditEventRecord> append(AuditEventRecord event) {
        validatePendingEvent(event);
        return storage.execute(connection -> appendInTransaction(connection, event));
    }

    static AuditEventRecord appendInTransaction(Connection connection, AuditEventRecord event)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        validatePendingEvent(event);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO audit_events(aggregate_type, aggregate_id, event_type, "
                        + "actor_type, actor_id, detail_json, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, event.aggregateType());
            statement.setString(2, event.aggregateId());
            statement.setString(3, event.eventType());
            statement.setString(4, event.actorType());
            if (event.actorId() == null) {
                statement.setNull(5, Types.VARCHAR);
            } else {
                statement.setString(5, event.actorId());
            }
            statement.setString(6, event.detailJson());
            statement.setLong(7, event.occurredAtEpochMillis());
            statement.executeUpdate();
        }
        long auditId;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT last_insert_rowid()");
                ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new SQLException("SQLite did not return the inserted audit id");
            }
            auditId = resultSet.getLong(1);
        }
        return new AuditEventRecord(
                auditId,
                event.aggregateType(),
                event.aggregateId(),
                event.eventType(),
                event.actorType(),
                event.actorId(),
                event.detailJson(),
                event.occurredAtEpochMillis());
    }

    @Override
    public CompletionStage<Page<AuditEventRecord>> listByAggregate(
            String aggregateType, String aggregateId, PageRequest request) {
        String normalizedType = normalizeLookup(
                aggregateType, "aggregateType", AuditEventRecord.MAX_TYPE_LENGTH);
        String normalizedId = normalizeLookup(
                aggregateId, "aggregateId", AuditEventRecord.MAX_ID_LENGTH);
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> {
            List<AuditEventRecord> records = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM audit_events WHERE aggregate_type = ? AND aggregate_id = ? "
                            + "ORDER BY audit_id DESC LIMIT ? OFFSET ?")) {
                statement.setString(1, normalizedType);
                statement.setString(2, normalizedId);
                statement.setInt(3, request.limit() + 1);
                statement.setInt(4, request.offset());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        records.add(readRecord(resultSet));
                    }
                }
            }
            boolean hasMore = records.size() > request.limit();
            if (hasMore) {
                records.remove(records.size() - 1);
            }
            return new Page<>(records, request.offset(), request.limit(), hasMore);
        });
    }

    private static void validatePendingEvent(AuditEventRecord event) {
        Objects.requireNonNull(event, "event");
        if (event.auditId() != 0L) {
            throw new IllegalArgumentException("New audit events must not already have an id");
        }
    }

    private static AuditEventRecord readRecord(ResultSet resultSet) throws SQLException {
        return new AuditEventRecord(
                resultSet.getLong("audit_id"),
                resultSet.getString("aggregate_type"),
                resultSet.getString("aggregate_id"),
                resultSet.getString("event_type"),
                resultSet.getString("actor_type"),
                resultSet.getString("actor_id"),
                resultSet.getString("detail_json"),
                resultSet.getLong("occurred_at"));
    }

    private static String normalizeLookup(String value, String name, int maxLength) {
        Objects.requireNonNull(value, name);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return normalized;
    }
}
