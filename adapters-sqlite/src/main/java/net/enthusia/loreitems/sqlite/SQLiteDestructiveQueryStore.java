package net.enthusia.loreitems.sqlite;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.Metrics;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.OperationView;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.Preview;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.PreviewRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.TargetView;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;

final class SQLiteDestructiveQueryStore {
    private static final String OPERATION_SELECT = "SELECT operation.*, "
            + "SUM(CASE WHEN target.state = 'PENDING' THEN 1 ELSE 0 END) pending_count, "
            + "SUM(CASE WHEN target.state IN ('CLAIMED', 'APPLIED', 'VERIFIED') "
            + "THEN 1 ELSE 0 END) claimed_count, "
            + "SUM(CASE WHEN target.state = 'REVIEW_REQUIRED' THEN 1 ELSE 0 END) review_count, "
            + "SUM(CASE WHEN target.state = 'COMPLETED' THEN 1 ELSE 0 END) completed_count, "
            + "SUM(CASE WHEN target.state = 'ABORTED' THEN 1 ELSE 0 END) aborted_count "
            + "FROM destructive_operations operation "
            + "LEFT JOIN destructive_targets target "
            + "ON target.operation_id = operation.operation_id ";
    private static final String OPERATION_GROUP = " GROUP BY operation.operation_id ";
    private static final String DEFINITION_TARGET_COUNTS =
            "SELECT COUNT(*) target_count, "
                    + "SUM(CASE WHEN current.state IS NULL OR current.state <> 'CONFIRMED_NOW' "
                    + "THEN 1 ELSE 0 END) inaccessible_count FROM lore_instances instance "
                    + "LEFT JOIN instance_current_state current "
                    + "ON current.instance_id = instance.instance_id "
                    + "WHERE instance.definition_id = ? AND instance.lifecycle_state = 'ACTIVE'";
    private static final String EXACT_TARGET_COUNTS = DEFINITION_TARGET_COUNTS
            + " AND instance.instance_id = ?";
    private static final String TARGET_SNAPSHOT_SELECT =
            "SELECT instance.instance_id, instance.applied_revision, current.state, "
                    + "current.location_type, current.location_key, current.container_path "
                    + "FROM lore_instances instance LEFT JOIN instance_current_state current "
                    + "ON current.instance_id = instance.instance_id "
                    + "WHERE instance.definition_id = ? AND instance.lifecycle_state = 'ACTIVE'";
    private final SQLiteStorageRuntime storage;

    SQLiteDestructiveQueryStore(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    CompletionStage<Optional<Preview>> preview(PreviewRequest request) {
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> preview(connection, request));
    }

    CompletionStage<Page<OperationView>> listOperations(PageRequest request) {
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> listOperations(connection, request));
    }

    CompletionStage<Page<TargetView>> listTargets(UUID operationId, PageRequest request) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> listTargets(connection, operationId, request));
    }

    CompletionStage<Metrics> metrics(Instant now) {
        Objects.requireNonNull(now, "now");
        return storage.execute(connection -> metrics(connection, now.toEpochMilli()));
    }

    Optional<Preview> preview(Connection connection, PreviewRequest request) throws SQLException {
        DefinitionSnapshot definition = readDefinition(connection, request);
        if (definition == null || definition.deleted()) {
            return Optional.empty();
        }
        TargetCounts targets = readTargetCounts(connection, request);
        if (request.operationType().exactInstanceRequired() && targets.targetCount() != 1L) {
            return Optional.empty();
        }
        long queued = countQueuedWork(connection, request);
        long anomalies = countAnomalies(connection, request);
        String targetSnapshotToken = targetSnapshotToken(connection, request);
        return Optional.of(toPreview(
                request,
                definition,
                targets,
                queued,
                anomalies,
                targetSnapshotToken));
    }

    Optional<OperationView> findByIdempotencyKey(Connection connection, String key)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                OPERATION_SELECT + "WHERE operation.idempotency_key = ?" + OPERATION_GROUP)) {
            statement.setString(1, key);
            return readOptionalOperation(statement);
        }
    }

    Optional<OperationView> findOperation(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                OPERATION_SELECT + "WHERE operation.operation_id = ?" + OPERATION_GROUP)) {
            statement.setString(1, operationId.toString());
            return readOptionalOperation(statement);
        }
    }

    Optional<TargetView> findTarget(
            Connection connection,
            UUID operationId,
            LoreInstanceId instanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM destructive_targets WHERE operation_id = ? AND instance_id = ?")) {
            statement.setString(1, operationId.toString());
            statement.setString(2, instanceId.value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(SQLiteDestructiveRows.readTarget(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static Preview toPreview(
            PreviewRequest request,
            DefinitionSnapshot definition,
            TargetCounts targets,
            long queued,
            long anomalies,
            String targetSnapshotToken) {
        return new Preview(
                request.operationType(),
                request.definitionId(),
                definition.lookupKey(),
                definition.displayName(),
                definition.currentRevision(),
                request.exactInstanceId(),
                targets.targetCount(),
                targets.inaccessibleCount(),
                queued,
                anomalies,
                confirmationToken(
                        request,
                        definition,
                        targets,
                        queued,
                        anomalies,
                        targetSnapshotToken));
    }

    private static DefinitionSnapshot readDefinition(
            Connection connection,
            PreviewRequest request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT lookup_key, display_name, current_revision, deleted_at "
                        + "FROM lore_definitions WHERE definition_id = ?")) {
            statement.setString(1, request.definitionId().value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                resultSet.getLong("deleted_at");
                boolean deleted = !resultSet.wasNull();
                return new DefinitionSnapshot(
                        new DefinitionKey(resultSet.getString("lookup_key")),
                        resultSet.getString("display_name"),
                        new TemplateRevision(resultSet.getLong("current_revision")),
                        deleted);
            }
        }
    }

    private static TargetCounts readTargetCounts(
            Connection connection,
            PreviewRequest request) throws SQLException {
        return request.exactInstanceId() == null
                ? readDefinitionTargetCounts(connection, request)
                : readExactTargetCounts(connection, request);
    }

    private static TargetCounts readDefinitionTargetCounts(
            Connection connection,
            PreviewRequest request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DEFINITION_TARGET_COUNTS)) {
            statement.setString(1, request.definitionId().value().toString());
            return readTargetCounts(statement);
        }
    }

    private static TargetCounts readExactTargetCounts(
            Connection connection,
            PreviewRequest request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(EXACT_TARGET_COUNTS)) {
            statement.setString(1, request.definitionId().value().toString());
            statement.setString(2, request.exactInstanceId().value().toString());
            return readTargetCounts(statement);
        }
    }

    private static TargetCounts readTargetCounts(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return new TargetCounts(
                    resultSet.getLong("target_count"),
                    resultSet.getLong("inaccessible_count"));
        }
    }

    private static String targetSnapshotToken(
            Connection connection,
            PreviewRequest request) throws SQLException {
        MessageDigest digest = sha256();
        String sql = TARGET_SNAPSHOT_SELECT
                + (request.exactInstanceId() == null ? "" : " AND instance.instance_id = ?")
                + " ORDER BY instance.instance_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, request.definitionId().value().toString());
            if (request.exactInstanceId() != null) {
                statement.setString(2, request.exactInstanceId().value().toString());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    updateDigest(digest, resultSet.getString("instance_id"));
                    updateDigest(digest, Long.toString(resultSet.getLong("applied_revision")));
                    updateDigest(digest, resultSet.getString("state"));
                    updateDigest(digest, resultSet.getString("location_type"));
                    updateDigest(digest, resultSet.getString("location_key"));
                    updateDigest(digest, resultSet.getString("container_path"));
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateDigest(MessageDigest digest, String value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) 1);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static long countQueuedWork(Connection connection, PreviewRequest request)
            throws SQLException {
        return request.exactInstanceId() == null
                ? countDefinitionQueuedWork(connection, request)
                : countExactQueuedWork(connection, request);
    }

    private static long countDefinitionQueuedWork(
            Connection connection,
            PreviewRequest request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM pending_mutations WHERE definition_id = ? "
                        + "AND state NOT IN ('COMPLETED', 'CANCELLED')")) {
            statement.setString(1, request.definitionId().value().toString());
            return count(statement);
        }
    }

    private static long countExactQueuedWork(
            Connection connection,
            PreviewRequest request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM pending_mutations WHERE definition_id = ? "
                        + "AND state NOT IN ('COMPLETED', 'CANCELLED') AND instance_id = ?")) {
            statement.setString(1, request.definitionId().value().toString());
            statement.setString(2, request.exactInstanceId().value().toString());
            return count(statement);
        }
    }

    private static long countAnomalies(Connection connection, PreviewRequest request)
            throws SQLException {
        return request.exactInstanceId() == null
                ? countDefinitionAnomalies(connection, request)
                : countExactAnomalies(connection, request);
    }

    private static long countDefinitionAnomalies(
            Connection connection,
            PreviewRequest request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM instance_anomalies WHERE definition_id = ? "
                        + "AND status IN ('OPEN', 'ACKNOWLEDGED')")) {
            statement.setString(1, request.definitionId().value().toString());
            return count(statement);
        }
    }

    private static long countExactAnomalies(
            Connection connection,
            PreviewRequest request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM instance_anomalies WHERE definition_id = ? "
                        + "AND status IN ('OPEN', 'ACKNOWLEDGED') AND instance_id = ?")) {
            statement.setString(1, request.definitionId().value().toString());
            statement.setString(2, request.exactInstanceId().value().toString());
            return count(statement);
        }
    }

    private static long count(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static Page<OperationView> listOperations(
            Connection connection,
            PageRequest request) throws SQLException {
        List<OperationView> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                OPERATION_SELECT + OPERATION_GROUP
                        + "ORDER BY operation.accepted_at DESC, operation.operation_id "
                        + "LIMIT ? OFFSET ?")) {
            statement.setInt(1, request.limit() + 1);
            statement.setInt(2, request.offset());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    values.add(SQLiteDestructiveRows.readOperation(resultSet));
                }
            }
        }
        return page(values, request);
    }

    private static Page<TargetView> listTargets(
            Connection connection,
            UUID operationId,
            PageRequest request) throws SQLException {
        List<TargetView> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM destructive_targets WHERE operation_id = ? "
                        + "ORDER BY created_at, instance_id LIMIT ? OFFSET ?")) {
            statement.setString(1, operationId.toString());
            statement.setInt(2, request.limit() + 1);
            statement.setInt(3, request.offset());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    values.add(SQLiteDestructiveRows.readTarget(resultSet));
                }
            }
        }
        return page(values, request);
    }

    private static Optional<OperationView> readOptionalOperation(PreparedStatement statement)
            throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next()
                    ? Optional.of(SQLiteDestructiveRows.readOperation(resultSet))
                    : Optional.empty();
        }
    }

    private static Metrics metrics(Connection connection, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT "
                        + "(SELECT COUNT(*) FROM destructive_operations WHERE state = 'ACTIVE') "
                        + "active_operations, "
                        + "(SELECT COUNT(*) FROM destructive_operations WHERE state = 'PAUSED') "
                        + "paused_operations, "
                        + "(SELECT COUNT(*) FROM destructive_targets WHERE state = 'PENDING') "
                        + "queued_targets, "
                        + "(SELECT COUNT(*) FROM destructive_targets WHERE state = 'CLAIMED') "
                        + "active_leases, "
                        + "(SELECT COUNT(*) FROM destructive_targets "
                        + "WHERE state = 'REVIEW_REQUIRED') review_targets, "
                        + "(SELECT MIN(created_at) FROM destructive_targets "
                        + "WHERE state = 'PENDING') oldest_created, "
                        + "(SELECT COALESCE(SUM(attempt_count), 0) FROM destructive_targets) "
                        + "total_attempts");
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            long oldest = resultSet.getLong("oldest_created");
            long age = resultSet.wasNull() ? 0L : Math.max(0L, now - oldest);
            return new Metrics(
                    resultSet.getLong("active_operations"),
                    resultSet.getLong("paused_operations"),
                    resultSet.getLong("queued_targets"),
                    resultSet.getLong("active_leases"),
                    resultSet.getLong("review_targets"),
                    age,
                    resultSet.getLong("total_attempts"));
        }
    }

    private static String confirmationToken(
            PreviewRequest request,
            DefinitionSnapshot definition,
            TargetCounts targets,
            long queued,
            long anomalies,
            String targetSnapshotToken) {
        String material = request.operationType().name() + '|' + request.definitionId().value()
                + '|' + (request.exactInstanceId() == null ? "-" : request.exactInstanceId().value())
                + '|' + definition.lookupKey().value() + '|' + definition.currentRevision().value()
                + '|' + targets.targetCount() + '|' + targets.inaccessibleCount()
                + '|' + queued + '|' + anomalies + '|' + targetSnapshotToken;
        MessageDigest digest = sha256();
        digest.update(material.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by Java", exception);
        }
    }

    private static <T> Page<T> page(List<T> values, PageRequest request) {
        boolean hasMore = values.size() > request.limit();
        if (hasMore) {
            values.remove(values.size() - 1);
        }
        return new Page<>(values, request.offset(), request.limit(), hasMore);
    }

    private record DefinitionSnapshot(
            DefinitionKey lookupKey,
            String displayName,
            TemplateRevision currentRevision,
            boolean deleted) {}

    private record TargetCounts(long targetCount, long inaccessibleCount) {}
}
