package net.enthusia.loreitems.sqlite;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.DefinitionRepository;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.LoreDefinitionId;

public final class SQLiteDefinitionRepository implements DefinitionRepository {
    private final SQLiteStorageRuntime storage;

    public SQLiteDefinitionRepository(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<Optional<LoreDefinitionId>> findActiveIdByKey(DefinitionKey key) {
        Objects.requireNonNull(key, "key");
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT definition_id FROM lore_definitions "
                            + "WHERE lookup_key = ? AND deleted_at IS NULL")) {
                statement.setString(1, key.value());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new LoreDefinitionId(
                            UUID.fromString(resultSet.getString("definition_id"))));
                }
            }
        });
    }
}
