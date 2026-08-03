package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.DefinitionRepository;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreDefinitionRevision;
import net.enthusia.loreitems.domain.TemplateRevision;

public final class SQLiteDefinitionRepository implements DefinitionRepository {
    private static final String DEFINITION_ID_ARGUMENT = "definitionId";
    private static final int SINGLE_UPDATED_ROW = 1;


    private final SQLiteStorageRuntime storage;

    public SQLiteDefinitionRepository(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<Void> create(
            LoreDefinition definition, LoreDefinitionRevision initialRevision) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(initialRevision, "initialRevision");
        if (!definition.active()) {
            throw new IllegalArgumentException("A new definition must be active");
        }
        if (!definition.id().equals(initialRevision.definitionId())) {
            throw new IllegalArgumentException("Initial revision belongs to another definition");
        }
        if (definition.currentRevision().value() != 1L
                || initialRevision.revision().value() != 1L) {
            throw new IllegalArgumentException("A new definition must begin at revision 1");
        }
        if (initialRevision.createdAtEpochMillis() < definition.createdAtEpochMillis()) {
            throw new IllegalArgumentException("Initial revision must not precede the definition");
        }
        return storage.execute(connection -> SQLiteTransactions.inTransaction(connection, transaction -> {
            insertDefinition(transaction, definition);
            insertRevision(transaction, initialRevision);
            return null;
        }));
    }

    @Override
    public CompletionStage<Optional<LoreDefinition>> findById(LoreDefinitionId definitionId) {
        Objects.requireNonNull(definitionId, DEFINITION_ID_ARGUMENT);
        return storage.execute(connection -> findDefinition(
                connection,
                "SELECT definition_id, lookup_key, display_name, current_revision, created_at, "
                        + "deleted_at FROM lore_definitions WHERE definition_id = ?",
                definitionId.value().toString()));
    }

    @Override
    public CompletionStage<Optional<LoreDefinition>> findActiveByKey(DefinitionKey key) {
        Objects.requireNonNull(key, "key");
        return storage.execute(connection -> findDefinition(
                connection,
                "SELECT definition_id, lookup_key, display_name, current_revision, created_at, "
                        + "deleted_at FROM lore_definitions "
                        + "WHERE lookup_key = ? AND deleted_at IS NULL",
                key.value()));
    }

    @Override
    public CompletionStage<Optional<LoreDefinitionRevision>> findRevision(
            LoreDefinitionId definitionId, TemplateRevision revision) {
        Objects.requireNonNull(definitionId, DEFINITION_ID_ARGUMENT);
        Objects.requireNonNull(revision, "revision");
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT definition_id, revision, codec_version, template_blob, created_at "
                            + "FROM lore_definition_revisions "
                            + "WHERE definition_id = ? AND revision = ?")) {
                statement.setString(1, definitionId.value().toString());
                statement.setLong(2, revision.value());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next()
                            ? Optional.of(readRevision(resultSet))
                            : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletionStage<Page<LoreDefinitionRevision>> listRevisions(
            LoreDefinitionId definitionId, PageRequest request) {
        Objects.requireNonNull(definitionId, DEFINITION_ID_ARGUMENT);
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> {
            List<LoreDefinitionRevision> revisions = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT definition_id, revision, codec_version, template_blob, created_at "
                            + "FROM lore_definition_revisions WHERE definition_id = ? "
                            + "ORDER BY revision DESC LIMIT ? OFFSET ?")) {
                statement.setString(1, definitionId.value().toString());
                statement.setInt(2, request.limit() + 1);
                statement.setInt(3, request.offset());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        revisions.add(readRevision(resultSet));
                    }
                }
            }
            return page(revisions, request);
        });
    }

    @Override
    public CompletionStage<Page<LoreDefinition>> listActive(PageRequest request) {
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> {
            List<LoreDefinition> definitions = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT definition_id, lookup_key, display_name, current_revision, created_at, "
                            + "deleted_at FROM lore_definitions WHERE deleted_at IS NULL "
                            + "ORDER BY lookup_key, definition_id LIMIT ? OFFSET ?")) {
                statement.setInt(1, request.limit() + 1);
                statement.setInt(2, request.offset());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        definitions.add(readDefinition(resultSet));
                    }
                }
            }
            return page(definitions, request);
        });
    }

    @Override
    public CompletionStage<Boolean> appendRevision(
            LoreDefinitionId definitionId,
            TemplateRevision expectedCurrentRevision,
            LoreDefinitionRevision newRevision) {
        Objects.requireNonNull(definitionId, DEFINITION_ID_ARGUMENT);
        Objects.requireNonNull(expectedCurrentRevision, "expectedCurrentRevision");
        Objects.requireNonNull(newRevision, "newRevision");
        if (!definitionId.equals(newRevision.definitionId())) {
            throw new IllegalArgumentException("Revision belongs to another definition");
        }
        TemplateRevision targetRevision = expectedCurrentRevision.next();
        if (!targetRevision.equals(newRevision.revision())) {
            throw new IllegalArgumentException(
                    "New revision must immediately follow the expected revision");
        }
        return storage.execute(connection -> SQLiteTransactions.inTransaction(connection, transaction -> {
            int updated;
            try (PreparedStatement statement = transaction.prepareStatement(
                    "UPDATE lore_definitions SET current_revision = ? "
                            + "WHERE definition_id = ? AND current_revision = ? "
                            + "AND deleted_at IS NULL")) {
                statement.setLong(1, targetRevision.value());
                statement.setString(2, definitionId.value().toString());
                statement.setLong(3, expectedCurrentRevision.value());
                updated = statement.executeUpdate();
            }
            if (updated != SINGLE_UPDATED_ROW) {
                return false;
            }
            insertRevision(transaction, newRevision);
            return true;
        }));
    }

    @Override
    public CompletionStage<Boolean> markDeleted(
            LoreDefinitionId definitionId,
            TemplateRevision expectedCurrentRevision,
            Instant deletedAt) {
        Objects.requireNonNull(definitionId, DEFINITION_ID_ARGUMENT);
        Objects.requireNonNull(expectedCurrentRevision, "expectedCurrentRevision");
        Objects.requireNonNull(deletedAt, "deletedAt");
        if (deletedAt.toEpochMilli() < 0L) {
            throw new IllegalArgumentException("deletedAt must not precede the Unix epoch");
        }
        return storage.execute(connection -> markDeletedInTransaction(
                connection, definitionId, expectedCurrentRevision, deletedAt));
    }

    static boolean markDeletedInTransaction(
            Connection connection,
            LoreDefinitionId definitionId,
            TemplateRevision expectedCurrentRevision,
            Instant deletedAt) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(definitionId, DEFINITION_ID_ARGUMENT);
        Objects.requireNonNull(expectedCurrentRevision, "expectedCurrentRevision");
        Objects.requireNonNull(deletedAt, "deletedAt");
        long deletedAtMillis = deletedAt.toEpochMilli();
        if (deletedAtMillis < 0L) {
            throw new IllegalArgumentException("deletedAt must not precede the Unix epoch");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE lore_definitions SET deleted_at = ? "
                        + "WHERE definition_id = ? AND current_revision = ? "
                        + "AND deleted_at IS NULL AND created_at <= ?")) {
            statement.setLong(1, deletedAtMillis);
            statement.setString(2, definitionId.value().toString());
            statement.setLong(3, expectedCurrentRevision.value());
            statement.setLong(4, deletedAtMillis);
            return statement.executeUpdate() == 1;
        }
    }

    private static Optional<LoreDefinition> findDefinition(
            Connection connection, String sql, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(readDefinition(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static void insertDefinition(Connection connection, LoreDefinition definition)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO lore_definitions(definition_id, lookup_key, display_name, "
                        + "current_revision, created_at, deleted_at) VALUES (?, ?, ?, ?, ?, NULL)")) {
            statement.setString(1, definition.id().value().toString());
            statement.setString(2, definition.key().value());
            statement.setString(3, definition.displayName());
            statement.setLong(4, definition.currentRevision().value());
            statement.setLong(5, definition.createdAtEpochMillis());
            statement.executeUpdate();
        }
    }

    private static void insertRevision(
            Connection connection, LoreDefinitionRevision revision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO lore_definition_revisions(definition_id, revision, codec_version, "
                        + "template_blob, created_at) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, revision.definitionId().value().toString());
            statement.setLong(2, revision.revision().value());
            statement.setInt(3, revision.codecVersion());
            statement.setBytes(4, revision.templateBlob());
            statement.setLong(5, revision.createdAtEpochMillis());
            statement.executeUpdate();
        }
    }

    private static LoreDefinition readDefinition(ResultSet resultSet) throws SQLException {
        long deletedAt = resultSet.getLong("deleted_at");
        Long deletedAtValue = resultSet.wasNull() ? null : deletedAt;
        return new LoreDefinition(
                new LoreDefinitionId(UUID.fromString(resultSet.getString("definition_id"))),
                new DefinitionKey(resultSet.getString("lookup_key")),
                resultSet.getString("display_name"),
                new TemplateRevision(resultSet.getLong("current_revision")),
                resultSet.getLong("created_at"),
                deletedAtValue);
    }

    private static LoreDefinitionRevision readRevision(ResultSet resultSet) throws SQLException {
        return new LoreDefinitionRevision(
                new LoreDefinitionId(UUID.fromString(resultSet.getString("definition_id"))),
                new TemplateRevision(resultSet.getLong("revision")),
                resultSet.getInt("codec_version"),
                resultSet.getBytes("template_blob"),
                resultSet.getLong("created_at"));
    }

    private static <T> Page<T> page(List<T> items, PageRequest request) {
        boolean hasMore = items.size() > request.limit();
        if (hasMore) {
            items.remove(items.size() - 1);
        }
        return new Page<>(items, request.offset(), request.limit(), hasMore);
    }
}
