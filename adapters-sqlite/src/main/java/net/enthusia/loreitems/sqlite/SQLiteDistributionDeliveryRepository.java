package net.enthusia.loreitems.sqlite;

import static net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientSupport.requireNonNegative;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import net.enthusia.loreitems.application.DistributionDeliveryRepository;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.PreparedDistributionDelivery;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientState;

/**
 * SQLite adapter for the bounded, exactly-once campaign delivery workflow.
 *
 * <p>The public repository owns argument validation and asynchronous storage dispatch. Transaction
 * details are split by lifecycle responsibility so each persistence state machine stays bounded and
 * independently reviewable without changing the durable protocol.</p>
 */
public final class SQLiteDistributionDeliveryRepository
        implements DistributionDeliveryRepository {
    private static final String NOW_ARGUMENT = "now";
    private static final String NEXT_ATTEMPT_ARGUMENT = "nextAttemptAt";
    private static final String REVIEWED_AT_ARGUMENT = "reviewedAt";
    private static final int MIN_LIMIT = 1;
    private static final int MIN_SLOT = 0;
    private static final int MAX_SLOT = 35;
    private static final int MAX_REVIEW_REASON = 4_096;
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private final SQLiteStorageRuntime storage;

    public SQLiteDistributionDeliveryRepository(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<Page<CampaignRecipient>> claimPending(
            String claimToken,
            Instant now,
            Duration lease,
            int limit) {
        String token = SQLiteDistributionRecipientSupport.normalizeClaimToken(claimToken);
        long nowMillis = requireNonNegative(
                Objects.requireNonNull(now, NOW_ARGUMENT), NOW_ARGUMENT);
        Objects.requireNonNull(lease, "lease");
        requireLimit(limit);
        long leaseMillis = lease.toMillis();
        if (leaseMillis < SQLiteDistributionRecipientSupport.MIN_LEASE_MILLIS) {
            throw new IllegalArgumentException("lease must be positive");
        }
        long expiresAt = Math.addExact(nowMillis, leaseMillis);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> SQLiteDistributionDeliveryClaimTransactions.claimPending(
                        transaction, token, nowMillis, expiresAt, limit)));
    }

    @Override
    public CompletionStage<Optional<PreparedDistributionDelivery>> prepareClaimed(
            CampaignRecipient recipient,
            Instant now) {
        requireClaimedRecipient(recipient);
        long nowMillis = requireNonNegative(
                Objects.requireNonNull(now, NOW_ARGUMENT), NOW_ARGUMENT);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> SQLiteDistributionDeliveryPreparationTransactions.prepareClaimed(
                        transaction, recipient, nowMillis)));
    }

    @Override
    public CompletionStage<Boolean> deferClaimed(
            CampaignRecipient recipient,
            CampaignRecipientState targetPendingState,
            Instant now,
            Instant nextAttemptAt) {
        requireClaimedRecipient(recipient);
        requirePendingTarget(targetPendingState);
        long nowMillis = requireNonNegative(
                Objects.requireNonNull(now, NOW_ARGUMENT), NOW_ARGUMENT);
        long nextMillis = requireNonNegative(
                Objects.requireNonNull(nextAttemptAt, NEXT_ATTEMPT_ARGUMENT),
                NEXT_ATTEMPT_ARGUMENT);
        requireOrderedRetry(nowMillis, nextMillis);
        return storage.execute(connection ->
                SQLiteDistributionDeliveryPreparationTransactions.deferClaimed(
                        connection, recipient, targetPendingState, nowMillis, nextMillis));
    }

    @Override
    public CompletionStage<Boolean> deferPrepared(
            PreparedDistributionDelivery delivery,
            CampaignRecipientState targetPendingState,
            Instant now,
            Instant nextAttemptAt) {
        Objects.requireNonNull(delivery, "delivery");
        requirePendingTarget(targetPendingState);
        long nowMillis = requireNonNegative(
                Objects.requireNonNull(now, NOW_ARGUMENT), NOW_ARGUMENT);
        long nextMillis = requireNonNegative(
                Objects.requireNonNull(nextAttemptAt, NEXT_ATTEMPT_ARGUMENT),
                NEXT_ATTEMPT_ARGUMENT);
        requireOrderedRetry(nowMillis, nextMillis);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> SQLiteDistributionDeliveryPreparationTransactions.deferPrepared(
                        transaction, delivery, targetPendingState, nowMillis, nextMillis)));
    }

    @Override
    public CompletionStage<Boolean> completePrepared(
            PreparedDistributionDelivery delivery,
            int inventorySlot,
            String afterFingerprint,
            Instant completedAt) {
        Objects.requireNonNull(delivery, "delivery");
        requireSlot(inventorySlot);
        String fingerprint = requireFingerprint(afterFingerprint);
        long completedMillis = requireNonNegative(
                Objects.requireNonNull(completedAt, "completedAt"), "completedAt");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> SQLiteDistributionDeliveryFinalizationTransactions.completePrepared(
                        transaction, delivery, inventorySlot, fingerprint, completedMillis)));
    }

    @Override
    public CompletionStage<Boolean> moveClaimedToReview(
            CampaignRecipient recipient,
            String reason,
            Instant reviewedAt) {
        requireClaimedRecipient(recipient);
        String normalizedReason = requireReason(reason);
        long reviewedMillis = requireNonNegative(
                Objects.requireNonNull(reviewedAt, REVIEWED_AT_ARGUMENT),
                REVIEWED_AT_ARGUMENT);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> SQLiteDistributionDeliveryFinalizationTransactions
                        .moveClaimedToReview(
                                transaction, recipient, normalizedReason, reviewedMillis)));
    }

    @Override
    public CompletionStage<Boolean> movePreparedToReview(
            PreparedDistributionDelivery delivery,
            String reason,
            Instant reviewedAt) {
        Objects.requireNonNull(delivery, "delivery");
        String normalizedReason = requireReason(reason);
        long reviewedMillis = requireNonNegative(
                Objects.requireNonNull(reviewedAt, REVIEWED_AT_ARGUMENT),
                REVIEWED_AT_ARGUMENT);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> SQLiteDistributionDeliveryFinalizationTransactions
                        .movePreparedToReview(
                                transaction, delivery, normalizedReason, reviewedMillis)));
    }

    @Override
    public CompletionStage<Integer> wakePlayer(UUID playerId, Instant now, int limit) {
        Objects.requireNonNull(playerId, "playerId");
        long nowMillis = requireNonNegative(
                Objects.requireNonNull(now, NOW_ARGUMENT), NOW_ARGUMENT);
        requireLimit(limit);
        return storage.execute(connection ->
                SQLiteDistributionDeliveryClaimTransactions.wakePlayer(
                        connection, playerId, nowMillis, limit));
    }

    @Override
    public CompletionStage<Integer> recoverExpiredClaims(Instant now, int limit) {
        long nowMillis = requireNonNegative(
                Objects.requireNonNull(now, NOW_ARGUMENT), NOW_ARGUMENT);
        requireLimit(limit);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> SQLiteDistributionDeliveryClaimTransactions.recoverExpiredClaims(
                        transaction, nowMillis, limit)));
    }

    private static void requireClaimedRecipient(CampaignRecipient recipient) {
        Objects.requireNonNull(recipient, "recipient");
        if (recipient.state() != CampaignRecipientState.RESERVED_IN_FLIGHT
                || recipient.playerId() == null
                || recipient.claimToken() == null
                || recipient.instanceId() != null) {
            throw new IllegalArgumentException(
                    "Recipient must be a claimed, unprepared campaign delivery");
        }
    }

    private static void requirePendingTarget(CampaignRecipientState target) {
        Objects.requireNonNull(target, "targetPendingState");
        if (target != CampaignRecipientState.QUEUED_OFFLINE
                && target != CampaignRecipientState.QUEUED_INVENTORY_FULL) {
            throw new IllegalArgumentException(
                    "Campaign deferral target must be a pending delivery state");
        }
    }

    private static void requireLimit(int limit) {
        if (limit < MIN_LIMIT || limit > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("limit is outside bounded page limits");
        }
    }

    private static void requireSlot(int slot) {
        if (slot < MIN_SLOT || slot > MAX_SLOT) {
            throw new IllegalArgumentException(
                    "inventorySlot must identify player storage");
        }
    }

    private static void requireOrderedRetry(long now, long nextAttemptAt) {
        if (nextAttemptAt < now) {
            throw new IllegalArgumentException("nextAttemptAt must not precede now");
        }
    }

    private static String requireFingerprint(String value) {
        Objects.requireNonNull(value, "afterFingerprint");
        String normalized = value.strip();
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "afterFingerprint must be a lowercase SHA-256 value");
        }
        return normalized;
    }

    private static String requireReason(String value) {
        Objects.requireNonNull(value, "reason");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_REVIEW_REASON) {
            throw new IllegalArgumentException("Invalid campaign review reason");
        }
        return normalized;
    }
}
