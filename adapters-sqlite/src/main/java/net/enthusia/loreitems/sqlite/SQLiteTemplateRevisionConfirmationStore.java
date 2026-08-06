package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import net.enthusia.loreitems.application.EncodedItemTemplate;
import net.enthusia.loreitems.application.TemplateRevisionConfirmation;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;

/** SQL mapping for idempotent template-editor confirmations and revision payloads. */
final class SQLiteTemplateRevisionConfirmationStore {
    private SQLiteTemplateRevisionConfirmationStore() {}

    static Optional<ConfirmationState> findConfirmation(
            Connection connection, UUID confirmationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT c.definition_id, c.expected_revision, c.target_revision, c.actor_id, "
                        + "before_revision.codec_version before_codec_version, "
                        + "before_revision.template_blob before_template_blob, "
                        + "after_revision.codec_version after_codec_version, "
                        + "after_revision.template_blob after_template_blob "
                        + "FROM template_edit_confirmations c "
                        + "JOIN lore_definition_revisions before_revision "
                        + "ON before_revision.definition_id = c.definition_id "
                        + "AND before_revision.revision = c.expected_revision "
                        + "JOIN lore_definition_revisions after_revision "
                        + "ON after_revision.definition_id = c.definition_id "
                        + "AND after_revision.revision = c.target_revision "
                        + "WHERE c.confirmation_id = ?")) {
            statement.setString(1, confirmationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ConfirmationState(
                        new LoreDefinitionId(UUID.fromString(
                                resultSet.getString("definition_id"))),
                        new TemplateRevision(resultSet.getLong("expected_revision")),
                        new TemplateRevision(resultSet.getLong("target_revision")),
                        UUID.fromString(resultSet.getString("actor_id")),
                        resultSet.getInt("before_codec_version"),
                        resultSet.getBytes("before_template_blob"),
                        resultSet.getInt("after_codec_version"),
                        resultSet.getBytes("after_template_blob")));
            }
        }
    }

    static void insertConfirmation(
            Connection connection, TemplateRevisionConfirmation confirmation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO template_edit_confirmations(confirmation_id, definition_id, "
                        + "expected_revision, target_revision, actor_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, confirmation.confirmationId().toString());
            statement.setString(2, confirmation.newRevision().definitionId().value().toString());
            statement.setLong(3, confirmation.expectedCurrentRevision().value());
            statement.setLong(4, confirmation.newRevision().revision().value());
            statement.setString(5, confirmation.actorId().toString());
            statement.setLong(6, confirmation.newRevision().createdAtEpochMillis());
            statement.executeUpdate();
        }
    }

    static Optional<EncodedRevision> findRevision(
            Connection connection,
            LoreDefinitionId definitionId,
            TemplateRevision revision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT codec_version, template_blob FROM lore_definition_revisions "
                        + "WHERE definition_id = ? AND revision = ?")) {
            statement.setString(1, definitionId.value().toString());
            statement.setLong(2, revision.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new EncodedRevision(
                        resultSet.getInt("codec_version"),
                        resultSet.getBytes("template_blob")));
            }
        }
    }

    record ConfirmationState(
            LoreDefinitionId definitionId,
            TemplateRevision expectedRevision,
            TemplateRevision targetRevision,
            UUID actorId,
            int beforeCodecVersion,
            byte[] beforeTemplateBlob,
            int afterCodecVersion,
            byte[] afterTemplateBlob) {
        ConfirmationState {
            beforeTemplateBlob = beforeTemplateBlob.clone();
            afterTemplateBlob = afterTemplateBlob.clone();
        }

        boolean matches(TemplateRevisionConfirmation confirmation) {
            return definitionId.equals(confirmation.newRevision().definitionId())
                    && expectedRevision.equals(confirmation.expectedCurrentRevision())
                    && targetRevision.equals(confirmation.newRevision().revision())
                    && actorId.equals(confirmation.actorId())
                    && beforeCodecVersion == confirmation.beforeTemplate().codecVersion()
                    && Arrays.equals(
                            beforeTemplateBlob, confirmation.beforeTemplate().payload())
                    && afterCodecVersion == confirmation.newRevision().codecVersion()
                    && Arrays.equals(
                            afterTemplateBlob, confirmation.newRevision().templateBlob());
        }
    }

    record EncodedRevision(int codecVersion, byte[] templateBlob) {
        EncodedRevision {
            templateBlob = templateBlob.clone();
        }

        boolean matches(EncodedItemTemplate template) {
            return codecVersion == template.codecVersion()
                    && Arrays.equals(templateBlob, template.payload());
        }
    }
}
