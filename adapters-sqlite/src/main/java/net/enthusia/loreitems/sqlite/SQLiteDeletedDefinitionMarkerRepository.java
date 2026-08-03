package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.DeletedDefinitionMarkerRepository;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.DeletedDefinitionMarker;
import net.enthusia.loreitems.domain.LoreDefinitionId;

public final class SQLiteDeletedDefinitionMarkerRepository
        implements DeletedDefinitionMarkerRepository {
    private static final int SINGLE_UPDATED_ROW = 1;


    private final SQLiteStorageRuntime storage;

    public SQLiteDeletedDefinitionMarkerRepository(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<Void> create(DeletedDefinitionMarker marker) {
        Objects.requireNonNull(marker, "marker");
        return storage.execute(connection -> {
            createInTransaction(connection, marker);
            return null;
        });
    }

    static void createInTransaction(Connection connection, DeletedDefinitionMarker marker)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(marker, "marker");
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO deleted_definition_markers("
                        + "definition_id, lookup_key, deleted_at) "
                        + "SELECT definition_id, lookup_key, deleted_at FROM lore_definitions "
                        + "WHERE definition_id = ? AND lookup_key = ? AND deleted_at = ?")) {
            statement.setString(1, marker.definitionId().value().toString());
            statement.setString(2, marker.lookupKey().value());
            statement.setLong(3, marker.deletedAtEpochMillis());
            if (statement.executeUpdate() == SINGLE_UPDATED_ROW) {
                return;
            }
        }
        Optional<DeletedDefinitionMarker> existing =
                findByDefinitionIdInTransaction(connection, marker.definitionId());
        if (existing.filter(marker::equals).isPresent()) {
            return;
        }
        throw new IllegalStateException(
                "Deleted definition marker must match a soft-deleted definition and existing marker");
    }

    @Override
    public CompletionStage<Optional<DeletedDefinitionMarker>> findByDefinitionId(
            LoreDefinitionId definitionId) {
        Objects.requireNonNull(definitionId, "definitionId");
        return storage.execute(connection ->
                findByDefinitionIdInTransaction(connection, definitionId));
    }

    private static Optional<DeletedDefinitionMarker> findByDefinitionIdInTransaction(
            Connection connection, LoreDefinitionId definitionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT definition_id, lookup_key, deleted_at "
                        + "FROM deleted_definition_markers WHERE definition_id = ?")) {
            statement.setString(1, definitionId.value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(readMarker(resultSet))
                        : Optional.empty();
            }
        }
    }

    @Override
    public CompletionStage<Page<DeletedDefinitionMarker>> listRecent(PageRequest request) {
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> listRecent(connection, request));
    }

    @Override
    public CompletionStage<Page<DeletedDefinitionMarker>> listByLookupKey(
            DefinitionKey lookupKey, PageRequest request) {
        Objects.requireNonNull(lookupKey, "lookupKey");
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> listByLookupKey(connection, lookupKey, request));
    }

    private static Page<DeletedDefinitionMarker> listRecent(
            Connection connection, PageRequest request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT definition_id, lookup_key, deleted_at "
                        + "FROM deleted_definition_markers "
                        + "ORDER BY deleted_at DESC, definition_id LIMIT ? OFFSET ?")) {
            statement.setInt(1, request.limit() + 1);
            statement.setInt(2, request.offset());
            return readPage(statement, request);
        }
    }

    private static Page<DeletedDefinitionMarker> listByLookupKey(
            Connection connection, DefinitionKey lookupKey, PageRequest request)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT definition_id, lookup_key, deleted_at "
                        + "FROM deleted_definition_markers WHERE lookup_key = ? "
                        + "ORDER BY deleted_at DESC, definition_id LIMIT ? OFFSET ?")) {
            statement.setString(1, lookupKey.value());
            statement.setInt(2, request.limit() + 1);
            statement.setInt(3, request.offset());
            return readPage(statement, request);
        }
    }

    private static Page<DeletedDefinitionMarker> readPage(
            PreparedStatement statement, PageRequest request) throws SQLException {
        List<DeletedDefinitionMarker> markers = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                markers.add(readMarker(resultSet));
            }
        }
        boolean hasMore = markers.size() > request.limit();
        if (hasMore) {
            markers.remove(markers.size() - 1);
        }
        return new Page<>(markers, request.offset(), request.limit(), hasMore);
    }

    private static DeletedDefinitionMarker readMarker(ResultSet resultSet) throws SQLException {
        return new DeletedDefinitionMarker(
                new LoreDefinitionId(UUID.fromString(resultSet.getString("definition_id"))),
                new DefinitionKey(resultSet.getString("lookup_key")),
                resultSet.getLong("deleted_at"));
    }
}
