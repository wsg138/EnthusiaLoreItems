package net.enthusia.loreitems.sqlite;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.InstanceRepository;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstance;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.LoreInstanceLifecycle;
import net.enthusia.loreitems.domain.TemplateRevision;

public final class SQLiteInstanceRepository implements InstanceRepository {
    private final SQLiteStorageRuntime storage;

    public SQLiteInstanceRepository(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<Void> create(LoreInstance instance) {
        Objects.requireNonNull(instance, "instance");
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO lore_instances(instance_id, definition_id, applied_revision, "
                            + "desired_revision, lifecycle_state, created_at, terminal_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, instance.id().value().toString());
                statement.setString(2, instance.definitionId().value().toString());
                statement.setLong(3, instance.appliedRevision().value());
                statement.setLong(4, instance.desiredRevision().value());
                statement.setString(5, instance.lifecycle().name());
                statement.setLong(6, instance.createdAtEpochMillis());
                if (instance.terminalAtEpochMillis() == null) {
                    statement.setNull(7, Types.BIGINT);
                } else {
                    statement.setLong(7, instance.terminalAtEpochMillis());
                }
                statement.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public CompletionStage<Optional<LoreInstance>> findById(LoreInstanceId instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT instance_id, definition_id, applied_revision, desired_revision, "
                            + "lifecycle_state, created_at, terminal_at "
                            + "FROM lore_instances WHERE instance_id = ?")) {
                statement.setString(1, instanceId.value().toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next()
                            ? Optional.of(readInstance(resultSet))
                            : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletionStage<Page<LoreInstance>> listByDefinition(
            LoreDefinitionId definitionId, PageRequest request) {
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> {
            List<LoreInstance> instances = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT instance_id, definition_id, applied_revision, desired_revision, "
                            + "lifecycle_state, created_at, terminal_at FROM lore_instances "
                            + "WHERE definition_id = ? ORDER BY created_at, instance_id "
                            + "LIMIT ? OFFSET ?")) {
                statement.setString(1, definitionId.value().toString());
                statement.setInt(2, request.limit() + 1);
                statement.setInt(3, request.offset());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        instances.add(readInstance(resultSet));
                    }
                }
            }
            boolean hasMore = instances.size() > request.limit();
            if (hasMore) {
                instances.remove(instances.size() - 1);
            }
            return new Page<>(instances, request.offset(), request.limit(), hasMore);
        });
    }

    @Override
    public CompletionStage<Boolean> compareAndSetRevisions(
            LoreInstanceId instanceId,
            TemplateRevision expectedAppliedRevision,
            TemplateRevision expectedDesiredRevision,
            TemplateRevision targetAppliedRevision,
            TemplateRevision targetDesiredRevision) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(expectedAppliedRevision, "expectedAppliedRevision");
        Objects.requireNonNull(expectedDesiredRevision, "expectedDesiredRevision");
        Objects.requireNonNull(targetAppliedRevision, "targetAppliedRevision");
        Objects.requireNonNull(targetDesiredRevision, "targetDesiredRevision");
        if (expectedDesiredRevision.compareTo(expectedAppliedRevision) < 0
                || targetAppliedRevision.compareTo(expectedAppliedRevision) < 0
                || targetDesiredRevision.compareTo(expectedDesiredRevision) < 0
                || targetDesiredRevision.compareTo(targetAppliedRevision) < 0) {
            throw new IllegalArgumentException("Instance revisions must advance monotonically");
        }
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE lore_instances SET applied_revision = ?, desired_revision = ? "
                            + "WHERE instance_id = ? AND applied_revision = ? "
                            + "AND desired_revision = ? AND lifecycle_state = 'ACTIVE'")) {
                statement.setLong(1, targetAppliedRevision.value());
                statement.setLong(2, targetDesiredRevision.value());
                statement.setString(3, instanceId.value().toString());
                statement.setLong(4, expectedAppliedRevision.value());
                statement.setLong(5, expectedDesiredRevision.value());
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletionStage<Boolean> compareAndSetLifecycle(
            LoreInstanceId instanceId,
            LoreInstanceLifecycle expected,
            LoreInstanceLifecycle target,
            Instant terminalAt) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(terminalAt, "terminalAt");
        expected.transitionTo(target);
        long terminalAtMillis = terminalAt.toEpochMilli();
        if (terminalAtMillis < 0L) {
            throw new IllegalArgumentException("terminalAt must not precede the Unix epoch");
        }
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE lore_instances SET lifecycle_state = ?, terminal_at = ? "
                            + "WHERE instance_id = ? AND lifecycle_state = ? "
                            + "AND terminal_at IS NULL AND created_at <= ?")) {
                statement.setString(1, target.name());
                statement.setLong(2, terminalAtMillis);
                statement.setString(3, instanceId.value().toString());
                statement.setString(4, expected.name());
                statement.setLong(5, terminalAtMillis);
                return statement.executeUpdate() == 1;
            }
        });
    }

    private static LoreInstance readInstance(ResultSet resultSet) throws SQLException {
        long terminalAt = resultSet.getLong("terminal_at");
        Long terminalAtValue = resultSet.wasNull() ? null : terminalAt;
        return new LoreInstance(
                new LoreInstanceId(UUID.fromString(resultSet.getString("instance_id"))),
                new LoreDefinitionId(UUID.fromString(resultSet.getString("definition_id"))),
                new TemplateRevision(resultSet.getLong("applied_revision")),
                new TemplateRevision(resultSet.getLong("desired_revision")),
                LoreInstanceLifecycle.valueOf(resultSet.getString("lifecycle_state")),
                resultSet.getLong("created_at"),
                terminalAtValue);
    }
}
