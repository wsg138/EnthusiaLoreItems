package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.HeldItemAdoptionPreparation;
import net.enthusia.loreitems.application.HeldItemAdoptionStore;
import net.enthusia.loreitems.application.PreparedHeldItemAdoption;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;

public final class SQLiteHeldItemAdoptionStore implements HeldItemAdoptionStore {
    static final String MUTATION_TYPE = "ADOPT_HELD_ITEM";

    private static final String AGGREGATE_TYPE = "lore_instance";
    private static final String ACTOR_TYPE = "player";
    private static final String PREPARED_EVENT = "held_item_adoption_prepared";
    private static final String COMPLETED_EVENT = "held_item_adopted";
    private static final String REVIEW_EVENT = "held_item_adoption_review_required";
    private static final String OBSERVATION_SOURCE = "held-item-adoption";
    private static final String REVIEW_REQUIRED_STATE = "REVIEW_REQUIRED";
    private static final String COMPLETED_STATE = "COMPLETED";
    private static final int SINGLE_ROW = 1;
    private static final int MAX_REVIEW_REASON_LENGTH = 4_096;
    private static final char FIRST_NON_CONTROL_CHARACTER = 0x20;
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private final SQLiteStorageRuntime storage;

    public SQLiteHeldItemAdoptionStore(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<Optional<PreparedHeldItemAdoption>> prepare(
            HeldItemAdoptionPreparation preparation) {
        Objects.requireNonNull(preparation, "preparation");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> prepareInTransaction(transaction, preparation)));
    }

    @Override
    public CompletionStage<Boolean> complete(
            PreparedHeldItemAdoption adoption,
            String afterFingerprint,
            Instant completedAt) {
        Objects.requireNonNull(adoption, "adoption");
        String normalizedFingerprint = requireFingerprint(afterFingerprint, "afterFingerprint");
        Objects.requireNonNull(completedAt, "completedAt");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> completeInTransaction(
                        transaction, adoption, normalizedFingerprint, completedAt.toEpochMilli())));
    }

    @Override
    public CompletionStage<Boolean> requireReview(
            PreparedHeldItemAdoption adoption,
            String reason,
            Instant reviewedAt) {
        Objects.requireNonNull(adoption, "adoption");
        String normalizedReason = requireReviewReason(reason);
        Objects.requireNonNull(reviewedAt, "reviewedAt");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> requireReviewInTransaction(
                        transaction, adoption, normalizedReason, reviewedAt.toEpochMilli())));
    }

    private static Optional<PreparedHeldItemAdoption> prepareInTransaction(
            Connection connection,
            HeldItemAdoptionPreparation preparation) throws SQLException {
        DefinitionRevision definition = findActiveDefinition(
                connection, preparation.request().definitionKey());
        if (definition == null) {
            return Optional.empty();
        }
        PreparedHeldItemAdoption adoption = new PreparedHeldItemAdoption(
                preparation.mutationId(),
                preparation.request().definitionKey(),
                definition.definitionId(),
                new LoreInstanceId(preparation.instanceId()),
                definition.revision(),
                preparation.request().playerId(),
                preparation.request().selectedSlot(),
                preparation.request().beforeFingerprint(),
                preparation.claimToken(),
                preparation.preparedAtEpochMillis(),
                preparation.claimExpiresAtEpochMillis());
        insertInstance(connection, adoption);
        insertMissingCurrentState(connection, adoption);
        insertClaimedMutation(connection, adoption);
        appendAudit(connection, adoption, PREPARED_EVENT, preparedDetail(adoption),
                adoption.preparedAtEpochMillis());
        return Optional.of(adoption);
    }

    private static boolean completeInTransaction(
            Connection connection,
            PreparedHeldItemAdoption adoption,
            String afterFingerprint,
            long completedAt) throws SQLException {
        if (!transitionMutation(
                connection, adoption, "CLAIMED", "APPLIED", completedAt, false)) {
            return false;
        }
        long observationId = insertObservation(connection, adoption, completedAt);
        updateCurrentState(connection, adoption, observationId, completedAt);
        requireTransition(connection, adoption, "APPLIED", "VERIFIED", completedAt, false);
        requireTransition(connection, adoption, "VERIFIED", "COMPLETED", completedAt, true);
        appendAudit(connection, adoption, COMPLETED_EVENT,
                completedDetail(adoption, afterFingerprint), completedAt);
        return true;
    }

    private static boolean requireReviewInTransaction(
            Connection connection,
            PreparedHeldItemAdoption adoption,
            String reason,
            long reviewedAt) throws SQLException {
        MutationState mutation = findMutation(connection, adoption);
        if (mutation == null) {
            return false;
        }
        if (REVIEW_REQUIRED_STATE.equals(mutation.state())) {
            return true;
        }
        if (COMPLETED_STATE.equals(mutation.state())
                || !adoption.claimToken().toString().equals(mutation.claimToken())) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE pending_mutations SET state = 'REVIEW_REQUIRED', claim_token = NULL, "
                        + "claim_expires_at = NULL, updated_at = ? "
                        + "WHERE mutation_id = ? AND mutation_type = ? AND definition_id = ? "
                        + "AND instance_id = ? AND claim_token = ? "
                        + "AND state IN ('CLAIMED', 'APPLIED', 'VERIFIED')")) {
            statement.setLong(1, reviewedAt);
            bindMutationIdentity(statement, 2, adoption);
            statement.setString(6, adoption.claimToken().toString());
            if (statement.executeUpdate() != SINGLE_ROW) {
                return false;
            }
        }
        appendAudit(connection, adoption, REVIEW_EVENT, reviewDetail(adoption, reason), reviewedAt);
        return true;
    }

    private static DefinitionRevision findActiveDefinition(
            Connection connection,
            DefinitionKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT definition_id, current_revision FROM lore_definitions "
                        + "WHERE lookup_key = ? AND deleted_at IS NULL")) {
            statement.setString(1, key.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new DefinitionRevision(
                        new LoreDefinitionId(UUID.fromString(
                                resultSet.getString("definition_id"))),
                        new TemplateRevision(resultSet.getLong("current_revision")));
            }
        }
    }

    private static void insertInstance(
            Connection connection,
            PreparedHeldItemAdoption adoption) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO lore_instances(instance_id, definition_id, applied_revision, "
                        + "desired_revision, lifecycle_state, created_at, terminal_at) "
                        + "VALUES (?, ?, ?, ?, 'ACTIVE', ?, NULL)")) {
            statement.setString(1, adoption.instanceId().value().toString());
            statement.setString(2, adoption.definitionId().value().toString());
            statement.setLong(3, adoption.appliedRevision().value());
            statement.setLong(4, adoption.appliedRevision().value());
            statement.setLong(5, adoption.preparedAtEpochMillis());
            statement.executeUpdate();
        }
    }

    private static void insertMissingCurrentState(
            Connection connection,
            PreparedHeldItemAdoption adoption) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO instance_current_state(instance_id, state, location_type, "
                        + "location_key, container_path, last_observation_id, state_revision, "
                        + "updated_at) VALUES (?, 'MISSING_UNRESOLVED', NULL, NULL, NULL, NULL, 0, ?)")) {
            statement.setString(1, adoption.instanceId().value().toString());
            statement.setLong(2, adoption.preparedAtEpochMillis());
            statement.executeUpdate();
        }
    }

    private static void insertClaimedMutation(
            Connection connection,
            PreparedHeldItemAdoption adoption) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO pending_mutations(mutation_id, mutation_type, definition_id, "
                        + "instance_id, desired_revision, state, claim_token, claim_expires_at, "
                        + "attempt_count, next_attempt_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CLAIMED', ?, ?, 1, NULL, ?, ?)")) {
            statement.setString(1, adoption.mutationId().toString());
            statement.setString(2, MUTATION_TYPE);
            statement.setString(3, adoption.definitionId().value().toString());
            statement.setString(4, adoption.instanceId().value().toString());
            statement.setInt(5, Math.toIntExact(adoption.appliedRevision().value()));
            statement.setString(6, adoption.claimToken().toString());
            statement.setLong(7, adoption.claimExpiresAtEpochMillis());
            statement.setLong(8, adoption.preparedAtEpochMillis());
            statement.setLong(9, adoption.preparedAtEpochMillis());
            statement.executeUpdate();
        }
    }

    private static long insertObservation(
            Connection connection,
            PreparedHeldItemAdoption adoption,
            long observedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO instance_observations(instance_id, definition_id, location_type, "
                        + "location_key, container_path, confidence, source, observed_at) "
                        + "VALUES (?, ?, 'PLAYER_INVENTORY', ?, ?, 'CONFIRMED_NOW', ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, adoption.instanceId().value().toString());
            statement.setString(2, adoption.definitionId().value().toString());
            statement.setString(3, adoption.playerId().toString());
            statement.setString(4, "hotbar:" + adoption.selectedSlot());
            statement.setString(5, OBSERVATION_SOURCE);
            statement.setLong(6, observedAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Adoption observation did not return an identifier");
                }
                return keys.getLong(1);
            }
        }
    }

    private static void updateCurrentState(
            Connection connection,
            PreparedHeldItemAdoption adoption,
            long observationId,
            long completedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE instance_current_state SET state = 'CONFIRMED_NOW', "
                        + "location_type = 'PLAYER_INVENTORY', location_key = ?, "
                        + "container_path = ?, last_observation_id = ?, state_revision = 1, "
                        + "updated_at = ? WHERE instance_id = ? AND state = 'MISSING_UNRESOLVED' "
                        + "AND state_revision = 0 AND last_observation_id IS NULL AND updated_at <= ?")) {
            statement.setString(1, adoption.playerId().toString());
            statement.setString(2, "hotbar:" + adoption.selectedSlot());
            statement.setLong(3, observationId);
            statement.setLong(4, completedAt);
            statement.setString(5, adoption.instanceId().value().toString());
            statement.setLong(6, completedAt);
            if (statement.executeUpdate() != SINGLE_ROW) {
                throw new SQLException("Adoption current-state verification lost its expected state");
            }
        }
    }

    private static boolean transitionMutation(
            Connection connection,
            PreparedHeldItemAdoption adoption,
            String expected,
            String target,
            long now,
            boolean clearClaim) throws SQLException {
        String sql = clearClaim
                ? "UPDATE pending_mutations SET state = ?, claim_token = NULL, "
                        + "claim_expires_at = NULL, updated_at = ? WHERE mutation_id = ? "
                        + "AND mutation_type = ? AND definition_id = ? AND instance_id = ? "
                        + "AND state = ? AND claim_token = ? AND claim_expires_at > ?"
                : "UPDATE pending_mutations SET state = ?, updated_at = ? WHERE mutation_id = ? "
                        + "AND mutation_type = ? AND definition_id = ? AND instance_id = ? "
                        + "AND state = ? AND claim_token = ? AND claim_expires_at > ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, target);
            statement.setLong(2, now);
            bindMutationIdentity(statement, 3, adoption);
            statement.setString(7, expected);
            statement.setString(8, adoption.claimToken().toString());
            statement.setLong(9, now);
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    private static void requireTransition(
            Connection connection,
            PreparedHeldItemAdoption adoption,
            String expected,
            String target,
            long now,
            boolean clearClaim) throws SQLException {
        if (!transitionMutation(connection, adoption, expected, target, now, clearClaim)) {
            throw new SQLException("Adoption mutation lost transition " + expected + " -> " + target);
        }
    }

    private static MutationState findMutation(
            Connection connection,
            PreparedHeldItemAdoption adoption) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state, claim_token FROM pending_mutations WHERE mutation_id = ? "
                        + "AND mutation_type = ? AND definition_id = ? AND instance_id = ?")) {
            bindMutationIdentity(statement, 1, adoption);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? new MutationState(
                                resultSet.getString("state"),
                                resultSet.getString("claim_token"))
                        : null;
            }
        }
    }

    private static void bindMutationIdentity(
            PreparedStatement statement,
            int firstIndex,
            PreparedHeldItemAdoption adoption) throws SQLException {
        statement.setString(firstIndex, adoption.mutationId().toString());
        statement.setString(firstIndex + 1, MUTATION_TYPE);
        statement.setString(firstIndex + 2, adoption.definitionId().value().toString());
        statement.setString(firstIndex + 3, adoption.instanceId().value().toString());
    }

    private static void appendAudit(
            Connection connection,
            PreparedHeldItemAdoption adoption,
            String eventType,
            String detail,
            long occurredAt) throws SQLException {
        SQLiteAuditRepository.appendInTransaction(connection, AuditEventRecord.pending(
                AGGREGATE_TYPE,
                adoption.instanceId().value().toString(),
                eventType,
                ACTOR_TYPE,
                adoption.playerId().toString(),
                detail,
                occurredAt));
    }

    private static String preparedDetail(PreparedHeldItemAdoption adoption) {
        return "{\"definitionKey\":\"" + adoption.definitionKey().value()
                + "\",\"mutationId\":\"" + adoption.mutationId()
                + "\",\"playerId\":\"" + adoption.playerId()
                + "\",\"slot\":" + adoption.selectedSlot()
                + ",\"revision\":" + adoption.appliedRevision().value()
                + ",\"beforeFingerprint\":\"" + adoption.beforeFingerprint() + "\"}";
    }

    private static String completedDetail(
            PreparedHeldItemAdoption adoption,
            String afterFingerprint) {
        return "{\"mutationId\":\"" + adoption.mutationId()
                + "\",\"slot\":" + adoption.selectedSlot()
                + ",\"beforeFingerprint\":\"" + adoption.beforeFingerprint()
                + "\",\"afterFingerprint\":\"" + afterFingerprint + "\"}";
    }

    private static String reviewDetail(
            PreparedHeldItemAdoption adoption,
            String reason) {
        return "{\"mutationId\":\"" + adoption.mutationId()
                + "\",\"slot\":" + adoption.selectedSlot()
                + ",\"reason\":\"" + escapeJson(reason) + "\"}";
    }

    private static String requireFingerprint(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.strip();
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return normalized;
    }

    private static String requireReviewReason(String reason) {
        Objects.requireNonNull(reason, "reason");
        String normalized = reason.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_REVIEW_REASON_LENGTH) {
            throw new IllegalArgumentException("Invalid adoption review reason");
        }
        return normalized;
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < FIRST_NON_CONTROL_CHARACTER) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private record DefinitionRevision(
            LoreDefinitionId definitionId,
            TemplateRevision revision) {
        private DefinitionRevision {
            Objects.requireNonNull(definitionId, "definitionId");
            Objects.requireNonNull(revision, "revision");
        }
    }

    private record MutationState(String state, String claimToken) {
        private MutationState {
            Objects.requireNonNull(state, "state");
        }
    }
}
