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
import net.enthusia.loreitems.application.CampaignCancellationResult;
import net.enthusia.loreitems.application.DistributionCampaignRepository;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.DistributionCampaign;
import net.enthusia.loreitems.domain.DistributionCampaignState;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;

public final class SQLiteDistributionCampaignRepository
        implements DistributionCampaignRepository {
    private static final String NOW_ARGUMENT = "now";
    private static final int MIN_RECIPIENT_COUNT = 1;
    private static final int SINGLE_UPDATED_ROW = 1;
    private static final long UNIX_EPOCH_MILLIS = 0L;

    private final SQLiteStorageRuntime storage;

    public SQLiteDistributionCampaignRepository(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<Void> create(DistributionCampaign campaign) {
        Objects.requireNonNull(campaign, "campaign");
        if (campaign.state() != DistributionCampaignState.DRAFT
                || campaign.terminalAtEpochMillis() != null) {
            throw new IllegalArgumentException("A new distribution campaign must begin as DRAFT");
        }
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> {
                    insertDraftInTransaction(transaction, campaign);
                    insertRevisionSnapshotInTransaction(transaction, campaign);
                    return null;
                }));
    }

    static void insertDraftInTransaction(Connection connection, DistributionCampaign campaign)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO distribution_campaigns(campaign_id, source_fingerprint, "
                        + "source_name, display_name, definition_id, state, created_at, "
                        + "updated_at, terminal_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)")) {
            statement.setString(1, campaign.campaignId().toString());
            statement.setString(2, campaign.sourceFingerprint());
            statement.setString(3, campaign.sourceName());
            statement.setString(4, campaign.displayName());
            statement.setString(5, campaign.definitionId().value().toString());
            statement.setString(6, DistributionCampaignState.DRAFT.name());
            statement.setLong(7, campaign.createdAtEpochMillis());
            statement.setLong(8, campaign.updatedAtEpochMillis());
            statement.executeUpdate();
        }
    }

    static void insertRevisionSnapshotInTransaction(
            Connection connection, DistributionCampaign campaign) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO distribution_campaign_revision_snapshots("
                        + "campaign_id, definition_id, definition_revision, created_at) "
                        + "VALUES (?, ?, ?, ?)")) {
            statement.setString(1, campaign.campaignId().toString());
            statement.setString(2, campaign.definitionId().value().toString());
            statement.setLong(3, campaign.definitionRevision().value());
            statement.setLong(4, campaign.createdAtEpochMillis());
            statement.executeUpdate();
        }
    }

    @Override
    public CompletionStage<Optional<DistributionCampaign>> findById(UUID campaignId) {
        Objects.requireNonNull(campaignId, "campaignId");
        return storage.execute(connection -> findCampaignById(connection, campaignId));
    }

    @Override
    public CompletionStage<Optional<DistributionCampaign>> findBySourceFingerprint(
            String sourceFingerprint) {
        String normalized = DistributionCampaign.normalizeSourceFingerprint(sourceFingerprint);
        return storage.execute(connection -> findCampaignByFingerprint(connection, normalized));
    }

    @Override
    public CompletionStage<Page<DistributionCampaign>> list(PageRequest request) {
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> {
            List<DistributionCampaign> campaigns = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    selectColumns()
                            + " ORDER BY campaign.created_at DESC, campaign.campaign_id LIMIT ? OFFSET ?")) {
                statement.setInt(1, request.limit() + 1);
                statement.setInt(2, request.offset());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        campaigns.add(readCampaign(resultSet));
                    }
                }
            }
            return page(campaigns, request);
        });
    }

    @Override
    public CompletionStage<Boolean> transitionState(
            UUID campaignId,
            DistributionCampaignState expected,
            DistributionCampaignState target,
            Instant now) {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(now, NOW_ARGUMENT);
        expected.transitionTo(target);
        if (target == DistributionCampaignState.CANCELLED) {
            throw new IllegalArgumentException(
                    "Campaign cancellation must use cancel so pending recipients are cancelled atomically");
        }
        long nowMillis = requireNonNegative(now, NOW_ARGUMENT);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> transitionStateInTransaction(
                        transaction, campaignId, expected, target, nowMillis)));
    }

    @Override
    public CompletionStage<CampaignCancellationResult> cancel(
            UUID campaignId,
            DistributionCampaignState expected,
            Instant now) {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(now, NOW_ARGUMENT);
        expected.transitionTo(DistributionCampaignState.CANCELLED);
        long nowMillis = requireNonNegative(now, NOW_ARGUMENT);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> cancelInTransaction(transaction, campaignId, expected, nowMillis)));
    }

    private static boolean transitionStateInTransaction(
            Connection connection,
            UUID campaignId,
            DistributionCampaignState expected,
            DistributionCampaignState target,
            long now) throws SQLException {
        if (expected == DistributionCampaignState.DRAFT
                && target == DistributionCampaignState.ACTIVE
                && !snapshotIsComplete(connection, campaignId)) {
            return false;
        }
        if (target == DistributionCampaignState.COMPLETED
                && !allRecipientsDelivered(connection, campaignId)) {
            return false;
        }

        String sql = target == DistributionCampaignState.COMPLETED
                ? "UPDATE distribution_campaigns SET state = ?, updated_at = ?, terminal_at = ? "
                        + "WHERE campaign_id = ? AND state = ? AND updated_at <= ? "
                        + "AND terminal_at IS NULL"
                : "UPDATE distribution_campaigns SET state = ?, updated_at = ? "
                        + "WHERE campaign_id = ? AND state = ? AND updated_at <= ? "
                        + "AND terminal_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, target.name());
            statement.setLong(2, now);
            if (target == DistributionCampaignState.COMPLETED) {
                statement.setLong(3, now);
                statement.setString(4, campaignId.toString());
                statement.setString(5, expected.name());
                statement.setLong(6, now);
            } else {
                statement.setString(3, campaignId.toString());
                statement.setString(4, expected.name());
                statement.setLong(5, now);
            }
            return statement.executeUpdate() == SINGLE_UPDATED_ROW;
        }
    }

    private static CampaignCancellationResult cancelInTransaction(
            Connection connection,
            UUID campaignId,
            DistributionCampaignState expected,
            long now) throws SQLException {
        try (PreparedStatement campaignUpdate = connection.prepareStatement(
                "UPDATE distribution_campaigns SET state = 'CANCELLED', updated_at = ?, "
                        + "terminal_at = ? WHERE campaign_id = ? AND state = ? "
                        + "AND updated_at <= ? AND terminal_at IS NULL")) {
            campaignUpdate.setLong(1, now);
            campaignUpdate.setLong(2, now);
            campaignUpdate.setString(3, campaignId.toString());
            campaignUpdate.setString(4, expected.name());
            campaignUpdate.setLong(5, now);
            if (campaignUpdate.executeUpdate() != SINGLE_UPDATED_ROW) {
                return new CampaignCancellationResult(false, 0);
            }
        }

        int cancelled;
        try (PreparedStatement recipientUpdate = connection.prepareStatement(
                "UPDATE distribution_recipients SET state = 'CANCELLED', "
                        + "claim_token = NULL, claim_expires_at = NULL, next_attempt_at = NULL, "
                        + "updated_at = ? WHERE campaign_id = ? "
                        + "AND state IN ('UNRESOLVED', 'QUEUED_OFFLINE', 'QUEUED_INVENTORY_FULL') "
                        + "AND updated_at <= ?")) {
            recipientUpdate.setLong(1, now);
            recipientUpdate.setString(2, campaignId.toString());
            recipientUpdate.setLong(3, now);
            cancelled = recipientUpdate.executeUpdate();
        }
        return new CampaignCancellationResult(true, cancelled);
    }

    private static boolean snapshotIsComplete(Connection connection, UUID campaignId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) AS recipient_count, MIN(snapshot_index) AS minimum_index, "
                        + "MAX(snapshot_index) AS maximum_index "
                        + "FROM distribution_recipients WHERE campaign_id = ?")) {
            statement.setString(1, campaignId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                long count = resultSet.getLong("recipient_count");
                if (count < MIN_RECIPIENT_COUNT) {
                    return false;
                }
                long minimum = resultSet.getLong("minimum_index");
                long maximum = resultSet.getLong("maximum_index");
                return minimum == 0L && maximum == count - 1L;
            }
        }
    }

    private static boolean allRecipientsDelivered(Connection connection, UUID campaignId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) AS total, "
                        + "SUM(CASE WHEN state = 'DELIVERED' THEN 1 ELSE 0 END) AS delivered "
                        + "FROM distribution_recipients WHERE campaign_id = ?")) {
            statement.setString(1, campaignId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                long total = resultSet.getLong("total");
                long delivered = resultSet.getLong("delivered");
                return total > 0L && total == delivered;
            }
        }
    }

    private static Optional<DistributionCampaign> findCampaignById(
            Connection connection, UUID campaignId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                selectColumns() + " WHERE campaign.campaign_id = ?")) {
            statement.setString(1, campaignId.toString());
            return readOptionalCampaign(statement);
        }
    }

    private static Optional<DistributionCampaign> findCampaignByFingerprint(
            Connection connection, String sourceFingerprint) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                selectColumns() + " WHERE campaign.source_fingerprint = ?")) {
            statement.setString(1, sourceFingerprint);
            return readOptionalCampaign(statement);
        }
    }

    private static Optional<DistributionCampaign> readOptionalCampaign(
            PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? Optional.of(readCampaign(resultSet)) : Optional.empty();
        }
    }

    private static String selectColumns() {
        return "SELECT campaign.campaign_id, campaign.source_fingerprint, campaign.source_name, "
                + "campaign.display_name, campaign.definition_id, revision.definition_revision, "
                + "campaign.state, campaign.created_at, campaign.updated_at, campaign.terminal_at "
                + "FROM distribution_campaigns campaign "
                + "JOIN distribution_campaign_revision_snapshots revision "
                + "ON revision.campaign_id = campaign.campaign_id";
    }

    private static DistributionCampaign readCampaign(ResultSet resultSet) throws SQLException {
        long terminalAt = resultSet.getLong("terminal_at");
        Long terminalAtValue = resultSet.wasNull() ? null : terminalAt;
        return new DistributionCampaign(
                UUID.fromString(resultSet.getString("campaign_id")),
                resultSet.getString("source_fingerprint"),
                resultSet.getString("source_name"),
                resultSet.getString("display_name"),
                new LoreDefinitionId(UUID.fromString(resultSet.getString("definition_id"))),
                new TemplateRevision(resultSet.getLong("definition_revision")),
                DistributionCampaignState.valueOf(resultSet.getString("state")),
                resultSet.getLong("created_at"),
                resultSet.getLong("updated_at"),
                terminalAtValue);
    }

    private static <T> Page<T> page(List<T> items, PageRequest request) {
        boolean hasMore = items.size() > request.limit();
        if (hasMore) {
            items.remove(items.size() - 1);
        }
        return new Page<>(items, request.offset(), request.limit(), hasMore);
    }

    private static long requireNonNegative(Instant instant, String name) {
        long value = instant.toEpochMilli();
        if (value < UNIX_EPOCH_MILLIS) {
            throw new IllegalArgumentException(name + " must not precede the Unix epoch");
        }
        return value;
    }
}
