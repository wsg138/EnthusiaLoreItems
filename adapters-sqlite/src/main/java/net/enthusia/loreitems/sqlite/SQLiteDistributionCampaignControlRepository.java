package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.CampaignCancellationResult;
import net.enthusia.loreitems.application.DistributionCampaignControlRepository;
import net.enthusia.loreitems.domain.DistributionCampaignState;

/** Commits campaign control state and required audit evidence in the same SQLite transaction. */
public final class SQLiteDistributionCampaignControlRepository
        implements DistributionCampaignControlRepository {
    private static final String AGGREGATE_TYPE = "DISTRIBUTION_CAMPAIGN";
    private static final int SINGLE_ROW = 1;
    private static final long PENDING_AUDIT_ID = 0L;
    private static final long UNIX_EPOCH_MILLIS = 0L;

    private final SQLiteStorageRuntime storage;

    public SQLiteDistributionCampaignControlRepository(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<Boolean> transitionWithAudit(
            UUID campaignId,
            DistributionCampaignState expected,
            DistributionCampaignState target,
            Instant now,
            AuditEventRecord auditEvent) {
        requireControlTransition(campaignId, expected, target, now);
        AuditEventRecord event = requireAudit(campaignId, now, auditEvent);
        long nowMillis = now.toEpochMilli();
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> transitionAndAudit(
                        transaction, campaignId, expected, target, nowMillis, event)));
    }

    @Override
    public CompletionStage<CampaignCancellationResult> cancelWithAudit(
            UUID campaignId,
            DistributionCampaignState expected,
            Instant now,
            String eventType,
            String actorType,
            String actorId) {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(now, "now");
        expected.transitionTo(DistributionCampaignState.CANCELLED);
        long nowMillis = requireNonNegative(now);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> cancelAndAudit(
                        transaction,
                        campaignId,
                        expected,
                        nowMillis,
                        eventType,
                        actorType,
                        actorId)));
    }

    private static boolean transitionAndAudit(
            Connection connection,
            UUID campaignId,
            DistributionCampaignState expected,
            DistributionCampaignState target,
            long now,
            AuditEventRecord event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_campaigns SET state = ?, updated_at = ? "
                        + "WHERE campaign_id = ? AND state = ? AND updated_at <= ? "
                        + "AND terminal_at IS NULL")) {
            statement.setString(1, target.name());
            statement.setLong(2, now);
            statement.setString(3, campaignId.toString());
            statement.setString(4, expected.name());
            statement.setLong(5, now);
            if (statement.executeUpdate() != SINGLE_ROW) {
                return false;
            }
        }
        SQLiteAuditRepository.appendInTransaction(connection, event);
        return true;
    }

    private static CampaignCancellationResult cancelAndAudit(
            Connection connection,
            UUID campaignId,
            DistributionCampaignState expected,
            long now,
            String eventType,
            String actorType,
            String actorId) throws SQLException {
        if (!cancelCampaign(connection, campaignId, expected, now)) {
            return new CampaignCancellationResult(false, 0);
        }
        int cancelled = cancelPendingRecipients(connection, campaignId, now);
        SQLiteAuditRepository.appendInTransaction(
                connection,
                AuditEventRecord.pending(
                        AGGREGATE_TYPE,
                        campaignId.toString(),
                        eventType,
                        actorType,
                        actorId,
                        "{\"recipientsCancelled\":" + cancelled + "}",
                        now));
        return new CampaignCancellationResult(true, cancelled);
    }

    private static boolean cancelCampaign(
            Connection connection,
            UUID campaignId,
            DistributionCampaignState expected,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_campaigns SET state = 'CANCELLED', updated_at = ?, "
                        + "terminal_at = ? WHERE campaign_id = ? AND state = ? "
                        + "AND updated_at <= ? AND terminal_at IS NULL")) {
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.setString(3, campaignId.toString());
            statement.setString(4, expected.name());
            statement.setLong(5, now);
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    private static int cancelPendingRecipients(
            Connection connection, UUID campaignId, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_recipients SET state = 'CANCELLED', "
                        + "claim_token = NULL, claim_expires_at = NULL, next_attempt_at = NULL, "
                        + "updated_at = ? WHERE campaign_id = ? "
                        + "AND state IN ('UNRESOLVED', 'QUEUED_OFFLINE', 'QUEUED_INVENTORY_FULL') "
                        + "AND updated_at <= ?")) {
            statement.setLong(1, now);
            statement.setString(2, campaignId.toString());
            statement.setLong(3, now);
            return statement.executeUpdate();
        }
    }

    private static void requireControlTransition(
            UUID campaignId,
            DistributionCampaignState expected,
            DistributionCampaignState target,
            Instant now) {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(now, "now");
        expected.transitionTo(target);
        requireNonNegative(now);
        boolean pause = expected == DistributionCampaignState.ACTIVE
                && target == DistributionCampaignState.PAUSED;
        boolean resume = expected == DistributionCampaignState.PAUSED
                && target == DistributionCampaignState.ACTIVE;
        if (!pause && !resume) {
            throw new IllegalArgumentException(
                    "Atomic campaign control only supports pause and resume transitions");
        }
    }

    private static AuditEventRecord requireAudit(
            UUID campaignId, Instant now, AuditEventRecord event) {
        Objects.requireNonNull(event, "auditEvent");
        if (!AGGREGATE_TYPE.equals(event.aggregateType())
                || !campaignId.toString().equals(event.aggregateId())
                || event.auditId() != PENDING_AUDIT_ID
                || event.occurredAtEpochMillis() != now.toEpochMilli()) {
            throw new IllegalArgumentException(
                    "Campaign control audit event does not match the atomic transition");
        }
        return event;
    }

    private static long requireNonNegative(Instant now) {
        long value = now.toEpochMilli();
        if (value < UNIX_EPOCH_MILLIS) {
            throw new IllegalArgumentException("now must not precede the Unix epoch");
        }
        return value;
    }
}
