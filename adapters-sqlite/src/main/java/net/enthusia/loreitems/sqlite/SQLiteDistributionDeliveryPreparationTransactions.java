package net.enthusia.loreitems.sqlite;

import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.ACTIVE_CAMPAIGN_EXISTS_SQL;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.CLEAR_CLAIM_SQL;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.RECIPIENT_PREDICATE_SQL;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.RESERVED_INSTANCE_PREDICATE_SQL;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.SINGLE_ROW;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.appendCampaignAudit;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.deliveryPath;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.escapeJson;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import net.enthusia.loreitems.application.EncodedItemTemplate;
import net.enthusia.loreitems.application.PreparedDistributionDelivery;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientState;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;

final class SQLiteDistributionDeliveryPreparationTransactions {
    private static final String QUEUED_SOURCE = "campaign-delivery-queued";
    private static final String PREPARED_EVENT = "DISTRIBUTION_RECIPIENT_PREPARED";

    private SQLiteDistributionDeliveryPreparationTransactions() {
    }

    static Optional<PreparedDistributionDelivery> prepareClaimed(
            Connection connection,
            CampaignRecipient recipient,
            long now) throws SQLException {
        PreparationSource source = findPreparationSource(connection, recipient, now);
        if (source == null) {
            return Optional.empty();
        }
        LoreInstanceId instanceId = new LoreInstanceId(UUID.randomUUID());
        insertInstance(connection, instanceId, source, now);
        long observationId = insertQueuedObservation(
                connection, recipient, instanceId, source, now);
        insertQueuedCurrentState(connection, recipient, instanceId, observationId, now);
        if (!attachInstance(connection, recipient, instanceId, now)) {
            throw new SQLException(
                    "Campaign recipient claim changed during durable preparation");
        }
        appendCampaignAudit(
                connection,
                recipient.campaignId(),
                PREPARED_EVENT,
                recipient.playerId().toString(),
                "{\"recipientKey\":\"" + escapeJson(recipient.recipientKey().value())
                        + "\",\"instanceId\":\"" + instanceId.value() + "\"}",
                now);
        return Optional.of(new PreparedDistributionDelivery(
                recipient.campaignId(),
                recipient.recipientKey(),
                instanceId,
                source.definitionId(),
                recipient.playerId(),
                source.revision(),
                source.template(),
                recipient.claimToken(),
                source.claimExpiresAt(),
                source.attemptCount(),
                now));
    }

    private static PreparationSource findPreparationSource(
            Connection connection,
            CampaignRecipient recipient,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT campaign.definition_id, snapshot.definition_revision, "
                        + "revision.codec_version, revision.template_blob, "
                        + "recipient.claim_expires_at, recipient.attempt_count "
                        + "FROM distribution_recipients recipient "
                        + "JOIN distribution_campaigns campaign "
                        + "ON campaign.campaign_id = recipient.campaign_id "
                        + "JOIN distribution_campaign_revision_snapshots snapshot "
                        + "ON snapshot.campaign_id = campaign.campaign_id "
                        + "JOIN lore_definition_revisions revision "
                        + "ON revision.definition_id = snapshot.definition_id "
                        + "AND revision.revision = snapshot.definition_revision "
                        + "WHERE recipient.campaign_id = ? AND recipient.recipient_key = ? "
                        + "AND recipient.state = 'RESERVED_IN_FLIGHT' "
                        + "AND recipient.player_id = ? AND recipient.instance_id IS NULL "
                        + "AND recipient.claim_token = ? AND recipient.claim_expires_at > ? "
                        + "AND campaign.state IN ('ACTIVE', 'PAUSED')")) {
            statement.setString(1, recipient.campaignId().toString());
            statement.setString(2, recipient.recipientKey().value());
            statement.setString(3, recipient.playerId().toString());
            statement.setString(4, recipient.claimToken());
            statement.setLong(5, now);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new PreparationSource(
                        new LoreDefinitionId(
                                UUID.fromString(resultSet.getString("definition_id"))),
                        new TemplateRevision(resultSet.getLong("definition_revision")),
                        new EncodedItemTemplate(
                                resultSet.getInt("codec_version"),
                                resultSet.getBytes("template_blob")),
                        resultSet.getLong("claim_expires_at"),
                        resultSet.getInt("attempt_count"));
            }
        }
    }

    private static void insertInstance(
            Connection connection,
            LoreInstanceId instanceId,
            PreparationSource source,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO lore_instances(instance_id, definition_id, "
                        + "applied_revision, desired_revision, lifecycle_state, created_at) "
                        + "VALUES (?, ?, ?, ?, 'ACTIVE', ?)")) {
            statement.setString(1, instanceId.value().toString());
            statement.setString(2, source.definitionId().value().toString());
            statement.setLong(3, source.revision().value());
            statement.setLong(4, source.revision().value());
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    private static long insertQueuedObservation(
            Connection connection,
            CampaignRecipient recipient,
            LoreInstanceId instanceId,
            PreparationSource source,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO instance_observations(instance_id, definition_id, "
                        + "location_type, location_key, container_path, confidence, "
                        + "source, observed_at) "
                        + "VALUES (?, ?, 'QUEUED_DELIVERY', ?, ?, 'CONFIRMED_NOW', ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, instanceId.value().toString());
            statement.setString(2, source.definitionId().value().toString());
            statement.setString(3, recipient.playerId().toString());
            statement.setString(
                    4, deliveryPath(recipient.campaignId(), recipient.recipientKey()));
            statement.setString(5, QUEUED_SOURCE);
            statement.setLong(6, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException(
                            "Campaign queued observation did not return an identifier");
                }
                return keys.getLong(1);
            }
        }
    }

    private static void insertQueuedCurrentState(
            Connection connection,
            CampaignRecipient recipient,
            LoreInstanceId instanceId,
            long observationId,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO instance_current_state(instance_id, state, location_type, "
                        + "location_key, container_path, last_observation_id, "
                        + "state_revision, updated_at) "
                        + "VALUES (?, 'CONFIRMED_NOW', 'QUEUED_DELIVERY', ?, ?, ?, 1, ?)")) {
            statement.setString(1, instanceId.value().toString());
            statement.setString(2, recipient.playerId().toString());
            statement.setString(
                    3, deliveryPath(recipient.campaignId(), recipient.recipientKey()));
            statement.setLong(4, observationId);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    private static boolean attachInstance(
            Connection connection,
            CampaignRecipient recipient,
            LoreInstanceId instanceId,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_recipients SET instance_id = ?, updated_at = ? "
                        + RECIPIENT_PREDICATE_SQL
                        + "AND state = 'RESERVED_IN_FLIGHT' AND player_id = ? "
                        + "AND instance_id IS NULL AND claim_token = ? "
                        + "AND claim_expires_at > ?")) {
            statement.setString(1, instanceId.value().toString());
            statement.setLong(2, now);
            statement.setString(3, recipient.campaignId().toString());
            statement.setString(4, recipient.recipientKey().value());
            statement.setString(5, recipient.playerId().toString());
            statement.setString(6, recipient.claimToken());
            statement.setLong(7, now);
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    static boolean deferClaimed(
            Connection connection,
            CampaignRecipient recipient,
            CampaignRecipientState target,
            long now,
            long nextAttemptAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_recipients SET state = ?, "
                        + CLEAR_CLAIM_SQL
                        + "next_attempt_at = ?, updated_at = ? "
                        + RECIPIENT_PREDICATE_SQL
                        + "AND state = 'RESERVED_IN_FLIGHT' AND instance_id IS NULL "
                        + "AND claim_token = ? AND claim_expires_at > ? "
                        + ACTIVE_CAMPAIGN_EXISTS_SQL
                        + "WHERE campaign.campaign_id = "
                        + "distribution_recipients.campaign_id "
                        + "AND campaign.state IN ('ACTIVE', 'PAUSED'))")) {
            statement.setString(1, target.name());
            statement.setLong(2, nextAttemptAt);
            statement.setLong(3, now);
            statement.setString(4, recipient.campaignId().toString());
            statement.setString(5, recipient.recipientKey().value());
            statement.setString(6, recipient.claimToken());
            statement.setLong(7, now);
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    static boolean deferPrepared(
            Connection connection,
            PreparedDistributionDelivery delivery,
            CampaignRecipientState target,
            long now,
            long nextAttemptAt) throws SQLException {
        if (!releasePreparedRecipient(connection, delivery, target, now, nextAttemptAt)) {
            return false;
        }
        deleteQueuedTracking(connection, delivery);
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM lore_instances WHERE instance_id = ? "
                        + "AND lifecycle_state = 'ACTIVE'")) {
            statement.setString(1, delivery.instanceId().value().toString());
            if (statement.executeUpdate() != SINGLE_ROW) {
                throw new SQLException(
                        "Prepared campaign instance could not be safely discarded");
            }
        }
        return true;
    }

    private static boolean releasePreparedRecipient(
            Connection connection,
            PreparedDistributionDelivery delivery,
            CampaignRecipientState target,
            long now,
            long nextAttemptAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_recipients SET state = ?, instance_id = NULL, "
                        + CLEAR_CLAIM_SQL
                        + "next_attempt_at = ?, updated_at = ? "
                        + RECIPIENT_PREDICATE_SQL
                        + RESERVED_INSTANCE_PREDICATE_SQL
                        + "AND claim_token = ? AND claim_expires_at > ? "
                        + ACTIVE_CAMPAIGN_EXISTS_SQL
                        + "WHERE campaign.campaign_id = "
                        + "distribution_recipients.campaign_id "
                        + "AND campaign.state IN ('ACTIVE', 'PAUSED'))")) {
            statement.setString(1, target.name());
            statement.setLong(2, nextAttemptAt);
            statement.setLong(3, now);
            statement.setString(4, delivery.campaignId().toString());
            statement.setString(5, delivery.recipientKey().value());
            statement.setString(6, delivery.instanceId().value().toString());
            statement.setString(7, delivery.claimToken());
            statement.setLong(8, now);
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    private static void deleteQueuedTracking(
            Connection connection,
            PreparedDistributionDelivery delivery) throws SQLException {
        deleteQueuedCurrentState(connection, delivery);
        deleteQueuedObservation(connection, delivery);
    }

    private static void deleteQueuedCurrentState(
            Connection connection,
            PreparedDistributionDelivery delivery) throws SQLException {
        try (PreparedStatement current = connection.prepareStatement(
                "DELETE FROM instance_current_state WHERE instance_id = ? "
                        + "AND state = 'CONFIRMED_NOW' "
                        + "AND location_type = 'QUEUED_DELIVERY' "
                        + "AND location_key = ? AND container_path = ?")) {
            current.setString(1, delivery.instanceId().value().toString());
            current.setString(2, delivery.playerId().toString());
            current.setString(
                    3, deliveryPath(delivery.campaignId(), delivery.recipientKey()));
            if (current.executeUpdate() != SINGLE_ROW) {
                throw new SQLException(
                        "Prepared campaign current-state evidence changed before deferral");
            }
        }
    }

    private static void deleteQueuedObservation(
            Connection connection,
            PreparedDistributionDelivery delivery) throws SQLException {
        try (PreparedStatement observations = connection.prepareStatement(
                "DELETE FROM instance_observations WHERE instance_id = ? AND source = ? "
                        + "AND location_type = 'QUEUED_DELIVERY' AND location_key = ? "
                        + "AND container_path = ?")) {
            observations.setString(1, delivery.instanceId().value().toString());
            observations.setString(2, QUEUED_SOURCE);
            observations.setString(3, delivery.playerId().toString());
            observations.setString(
                    4, deliveryPath(delivery.campaignId(), delivery.recipientKey()));
            if (observations.executeUpdate() != SINGLE_ROW) {
                throw new SQLException(
                        "Prepared campaign queued observation changed before deferral");
            }
        }
    }

    private record PreparationSource(
            LoreDefinitionId definitionId,
            TemplateRevision revision,
            EncodedItemTemplate template,
            long claimExpiresAt,
            int attemptCount) {
    }
}
