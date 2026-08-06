package net.enthusia.loreitems.sqlite;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.EncodedItemTemplate;
import net.enthusia.loreitems.application.TemplateManagementQueryStore;
import net.enthusia.loreitems.application.TemplateManagementSnapshot;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;

/** Bounded definition-specific editor query adapter. */
public final class SQLiteTemplateManagementQueryStore implements TemplateManagementQueryStore {
    private final SQLiteStorageRuntime storage;

    public SQLiteTemplateManagementQueryStore(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<Optional<TemplateManagementSnapshot>> findSnapshot(
            LoreDefinitionId definitionId) {
        Objects.requireNonNull(definitionId, "definitionId");
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT d.definition_id, d.lookup_key, d.display_name, d.current_revision, "
                            + "d.created_at, d.deleted_at, r.codec_version, r.template_blob, "
                            + "(SELECT COUNT(*) FROM lore_instances i WHERE i.definition_id = "
                            + "d.definition_id AND i.lifecycle_state = 'ACTIVE') instance_count, "
                            + "(SELECT COUNT(*) FROM instance_anomalies a WHERE a.definition_id = "
                            + "d.definition_id AND a.status IN ('OPEN', 'ACKNOWLEDGED')) anomaly_count, "
                            + "(SELECT COUNT(*) FROM pending_mutations m WHERE m.definition_id = "
                            + "d.definition_id AND m.mutation_type = 'TEMPLATE_UPDATE' "
                            + "AND m.state NOT IN ('COMPLETED', 'CANCELLED')) pending_count "
                            + "FROM lore_definitions d JOIN lore_definition_revisions r "
                            + "ON r.definition_id = d.definition_id "
                            + "AND r.revision = d.current_revision "
                            + "WHERE d.definition_id = ? AND d.deleted_at IS NULL")) {
                statement.setString(1, definitionId.value().toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next()
                            ? Optional.of(readSnapshot(resultSet))
                            : Optional.empty();
                }
            }
        });
    }

    private static TemplateManagementSnapshot readSnapshot(ResultSet resultSet)
            throws SQLException {
        LoreDefinition definition = new LoreDefinition(
                new LoreDefinitionId(UUID.fromString(resultSet.getString("definition_id"))),
                new DefinitionKey(resultSet.getString("lookup_key")),
                resultSet.getString("display_name"),
                new TemplateRevision(resultSet.getLong("current_revision")),
                resultSet.getLong("created_at"),
                null);
        EncodedItemTemplate template = new EncodedItemTemplate(
                resultSet.getInt("codec_version"),
                resultSet.getBytes("template_blob"));
        return new TemplateManagementSnapshot(
                definition,
                template,
                resultSet.getLong("instance_count"),
                resultSet.getLong("anomaly_count"),
                resultSet.getLong("pending_count"));
    }
}
