package net.enthusia.loreitems.sqlite;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.AnomalyRepository;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;

public final class SQLiteAnomalyRepository implements AnomalyRepository {
    private static final String ANOMALY_ID_ARGUMENT = "anomalyId";

    private final SQLiteStorageRuntime storage;

    public SQLiteAnomalyRepository(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<Void> create(InstanceAnomaly anomaly) {
        Objects.requireNonNull(anomaly, "anomaly");
        if (anomaly.status() != InstanceAnomaly.Status.OPEN
                || anomaly.stateRevision() != 0L) {
            throw new IllegalArgumentException(
                    "New anomalies must begin open at state revision zero");
        }
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO instance_anomalies(anomaly_id, instance_id, definition_id, "
                            + "anomaly_type, status, detail, first_seen_at, last_seen_at, "
                            + "acknowledged_at, acknowledged_by, resolved_at, "
                            + "resolution_detail, state_revision) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                bindAnomaly(statement, anomaly);
                statement.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public CompletionStage<Optional<InstanceAnomaly>> findById(UUID anomalyId) {
        Objects.requireNonNull(anomalyId, ANOMALY_ID_ARGUMENT);
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    selectColumns() + " WHERE anomaly_id = ?")) {
                statement.setString(1, anomalyId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next()
                            ? Optional.of(readAnomaly(resultSet))
                            : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletionStage<Page<InstanceAnomaly>> listActive(PageRequest request) {
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    selectColumns() + " WHERE status IN ('OPEN', 'ACKNOWLEDGED') "
                            + "ORDER BY last_seen_at DESC, anomaly_id LIMIT ? OFFSET ?")) {
                statement.setInt(1, request.limit() + 1);
                statement.setInt(2, request.offset());
                return readPage(statement, request);
            }
        });
    }

    @Override
    public CompletionStage<Page<InstanceAnomaly>> listActiveWarnings(PageRequest request) {
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    selectColumns() + " WHERE status IN ('OPEN', 'ACKNOWLEDGED') "
                            + "AND anomaly_type IN ('DUPLICATE_INSTANCE', 'MALFORMED_STACK') "
                            + "ORDER BY last_seen_at DESC, anomaly_id LIMIT ? OFFSET ?")) {
                statement.setInt(1, request.limit() + 1);
                statement.setInt(2, request.offset());
                return readPage(statement, request);
            }
        });
    }

    @Override
    public CompletionStage<Page<InstanceAnomaly>> listByInstance(
            LoreInstanceId instanceId, PageRequest request) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    selectColumns() + " WHERE instance_id = ? "
                            + "ORDER BY first_seen_at DESC, anomaly_id LIMIT ? OFFSET ?")) {
                statement.setString(1, instanceId.value().toString());
                statement.setInt(2, request.limit() + 1);
                statement.setInt(3, request.offset());
                return readPage(statement, request);
            }
        });
    }

    @Override
    public CompletionStage<Boolean> refresh(
            UUID anomalyId,
            long expectedStateRevision,
            String detail,
            long observedAtEpochMillis) {
        Objects.requireNonNull(anomalyId, ANOMALY_ID_ARGUMENT);
        String normalizedDetail = requireText(
                detail, "detail", InstanceAnomaly.MAX_DETAIL_LENGTH);
        if (expectedStateRevision < 0L || observedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("Invalid anomaly refresh bounds");
        }
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE instance_anomalies SET detail = ?, last_seen_at = ?, "
                            + "state_revision = state_revision + 1 "
                            + "WHERE anomaly_id = ? AND state_revision = ? "
                            + "AND status IN ('OPEN', 'ACKNOWLEDGED') "
                            + "AND last_seen_at <= ?")) {
                statement.setString(1, normalizedDetail);
                statement.setLong(2, observedAtEpochMillis);
                statement.setString(3, anomalyId.toString());
                statement.setLong(4, expectedStateRevision);
                statement.setLong(5, observedAtEpochMillis);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletionStage<Boolean> acknowledge(
            UUID anomalyId,
            long expectedStateRevision,
            String actorId,
            long acknowledgedAtEpochMillis) {
        Objects.requireNonNull(anomalyId, ANOMALY_ID_ARGUMENT);
        String normalizedActor = requireText(
                actorId, "actorId", InstanceAnomaly.MAX_ACTOR_LENGTH);
        if (expectedStateRevision < 0L || acknowledgedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("Invalid acknowledgement bounds");
        }
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE instance_anomalies SET status = 'ACKNOWLEDGED', "
                            + "acknowledged_at = ?, acknowledged_by = ?, "
                            + "state_revision = state_revision + 1 "
                            + "WHERE anomaly_id = ? AND state_revision = ? "
                            + "AND status = 'OPEN' AND first_seen_at <= ?")) {
                statement.setLong(1, acknowledgedAtEpochMillis);
                statement.setString(2, normalizedActor);
                statement.setString(3, anomalyId.toString());
                statement.setLong(4, expectedStateRevision);
                statement.setLong(5, acknowledgedAtEpochMillis);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletionStage<Boolean> resolve(
            UUID anomalyId,
            long expectedStateRevision,
            String resolutionDetail,
            long resolvedAtEpochMillis) {
        Objects.requireNonNull(anomalyId, ANOMALY_ID_ARGUMENT);
        String normalizedResolution = requireText(
                resolutionDetail, "resolutionDetail", InstanceAnomaly.MAX_DETAIL_LENGTH);
        if (expectedStateRevision < 0L || resolvedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("Invalid resolution bounds");
        }
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE instance_anomalies SET status = 'RESOLVED', resolved_at = ?, "
                            + "resolution_detail = ?, state_revision = state_revision + 1 "
                            + "WHERE anomaly_id = ? AND state_revision = ? "
                            + "AND status IN ('OPEN', 'ACKNOWLEDGED') "
                            + "AND last_seen_at <= ?")) {
                statement.setLong(1, resolvedAtEpochMillis);
                statement.setString(2, normalizedResolution);
                statement.setString(3, anomalyId.toString());
                statement.setLong(4, expectedStateRevision);
                statement.setLong(5, resolvedAtEpochMillis);
                return statement.executeUpdate() == 1;
            }
        });
    }

    private static String selectColumns() {
        return "SELECT anomaly_id, instance_id, definition_id, anomaly_type, status, "
                + "detail, first_seen_at, last_seen_at, acknowledged_at, acknowledged_by, "
                + "resolved_at, resolution_detail, state_revision FROM instance_anomalies";
    }

    private static Page<InstanceAnomaly> readPage(
            PreparedStatement statement, PageRequest request) throws SQLException {
        List<InstanceAnomaly> anomalies = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                anomalies.add(readAnomaly(resultSet));
            }
        }
        boolean hasMore = anomalies.size() > request.limit();
        if (hasMore) {
            anomalies.remove(anomalies.size() - 1);
        }
        return new Page<>(anomalies, request.offset(), request.limit(), hasMore);
    }

    private static void bindAnomaly(
            PreparedStatement statement, InstanceAnomaly anomaly) throws SQLException {
        statement.setString(1, anomaly.anomalyId().toString());
        if (anomaly.instanceId() == null) {
            statement.setNull(2, Types.VARCHAR);
        } else {
            statement.setString(2, anomaly.instanceId().value().toString());
        }
        statement.setString(3, anomaly.definitionId().value().toString());
        statement.setString(4, anomaly.type().name());
        statement.setString(5, anomaly.status().name());
        statement.setString(6, anomaly.detail());
        statement.setLong(7, anomaly.firstSeenAtEpochMillis());
        statement.setLong(8, anomaly.lastSeenAtEpochMillis());
        setNullableLong(statement, 9, anomaly.acknowledgedAtEpochMillis());
        statement.setString(10, anomaly.acknowledgedBy());
        setNullableLong(statement, 11, anomaly.resolvedAtEpochMillis());
        statement.setString(12, anomaly.resolutionDetail());
        statement.setLong(13, anomaly.stateRevision());
    }

    private static void setNullableLong(
            PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static InstanceAnomaly readAnomaly(ResultSet resultSet) throws SQLException {
        String instanceValue = resultSet.getString("instance_id");
        return new InstanceAnomaly(
                UUID.fromString(resultSet.getString("anomaly_id")),
                instanceValue == null
                        ? null
                        : new LoreInstanceId(UUID.fromString(instanceValue)),
                new LoreDefinitionId(UUID.fromString(resultSet.getString("definition_id"))),
                InstanceAnomaly.Type.valueOf(resultSet.getString("anomaly_type")),
                InstanceAnomaly.Status.valueOf(resultSet.getString("status")),
                resultSet.getString("detail"),
                resultSet.getLong("first_seen_at"),
                resultSet.getLong("last_seen_at"),
                nullableLong(resultSet, "acknowledged_at"),
                resultSet.getString("acknowledged_by"),
                nullableLong(resultSet, "resolved_at"),
                resultSet.getString("resolution_detail"),
                resultSet.getLong("state_revision"));
    }

    private static Long nullableLong(ResultSet resultSet, String column)
            throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static String requireText(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return normalized;
    }
}
