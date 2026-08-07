package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.DistributionCampaignStartRepository;
import net.enthusia.loreitems.application.DistributionCampaignStartRequest;
import net.enthusia.loreitems.application.DistributionCampaignStartResult;
import net.enthusia.loreitems.domain.DistributionCampaign;

public final class SQLiteDistributionCampaignStartRepository
        implements DistributionCampaignStartRepository {
    private static final String AGGREGATE_TYPE = "DISTRIBUTION_CAMPAIGN";
    private static final String START_EVENT = "DISTRIBUTION_CAMPAIGN_STARTED";

    private final SQLiteStorageRuntime storage;

    public SQLiteDistributionCampaignStartRepository(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<DistributionCampaignStartResult> start(
            DistributionCampaignStartRequest request) {
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection, transaction -> startInTransaction(transaction, request)));
    }

    private static DistributionCampaignStartResult startInTransaction(
            Connection connection,
            DistributionCampaignStartRequest request) throws SQLException {
        DistributionCampaign campaign = request.campaign();
        Optional<UUID> existing = findExistingCampaign(connection, campaign.sourceFingerprint());
        if (existing.isPresent()) {
            return new DistributionCampaignStartResult(
                    DistributionCampaignStartResult.Status.SOURCE_ALREADY_USED,
                    existing.orElseThrow());
        }
        requireSelectedActiveRevision(connection, campaign);
        SQLiteDistributionCampaignRepository.insertDraftInTransaction(connection, campaign);
        SQLiteDistributionCampaignRepository.insertRevisionSnapshotInTransaction(connection, campaign);
        SQLiteDistributionRecipientSupport.insertBatch(connection, request.recipients());
        appendStartAudit(connection, request);
        activateCampaign(connection, campaign);
        return new DistributionCampaignStartResult(
                DistributionCampaignStartResult.Status.STARTED,
                campaign.campaignId());
    }

    private static Optional<UUID> findExistingCampaign(
            Connection connection, String sourceFingerprint) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT campaign_id FROM distribution_campaigns WHERE source_fingerprint = ?")) {
            statement.setString(1, sourceFingerprint);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(UUID.fromString(resultSet.getString(1)))
                        : Optional.empty();
            }
        }
    }

    private static void requireSelectedActiveRevision(
            Connection connection, DistributionCampaign campaign) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT current_revision, deleted_at FROM lore_definitions WHERE definition_id = ?")) {
            statement.setString(1, campaign.definitionId().value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("Selected lore definition does not exist");
                }
                long currentRevision = resultSet.getLong("current_revision");
                Long deletedAt = nullableLong(resultSet, "deleted_at");
                if (deletedAt != null) {
                    throw new IllegalStateException("Selected lore definition is deleted");
                }
                if (currentRevision != campaign.definitionRevision().value()) {
                    throw new IllegalStateException(
                            "Selected lore definition revision changed before campaign start");
                }
            }
        }
    }

    private static void appendStartAudit(
            Connection connection, DistributionCampaignStartRequest request) throws SQLException {
        DistributionCampaign campaign = request.campaign();
        String detail = "{\"recipientCount\":" + request.recipients().size()
                + ",\"definitionRevision\":" + campaign.definitionRevision().value() + "}";
        SQLiteAuditRepository.appendInTransaction(
                connection,
                AuditEventRecord.pending(
                        AGGREGATE_TYPE,
                        campaign.campaignId().toString(),
                        START_EVENT,
                        request.actorType(),
                        request.actorId(),
                        detail,
                        campaign.updatedAtEpochMillis()));
    }

    private static void activateCampaign(Connection connection, DistributionCampaign campaign)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_campaigns SET state = 'ACTIVE', updated_at = ? "
                        + "WHERE campaign_id = ? AND state = 'DRAFT' AND terminal_at IS NULL")) {
            statement.setLong(1, campaign.updatedAtEpochMillis());
            statement.setString(2, campaign.campaignId().toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Failed to activate durable distribution campaign snapshot");
            }
        }
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }
}
