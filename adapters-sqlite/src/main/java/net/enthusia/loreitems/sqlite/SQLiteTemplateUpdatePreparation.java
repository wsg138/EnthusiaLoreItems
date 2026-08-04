package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.application.EncodedItemTemplate;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.PreparedTemplateUpdate;
import net.enthusia.loreitems.application.TemplateUpdatePrepareResult;
import net.enthusia.loreitems.domain.TemplateRevision;

final class SQLiteTemplateUpdatePreparation {
    private static final int SINGLE_UPDATED_ROW = 1;

    private SQLiteTemplateUpdatePreparation() {
    }

    static TemplateUpdatePrepareResult prepare(
            Connection connection,
            LoreItemIdentity observedIdentity,
            String claimToken,
            long now,
            long leaseMillis) throws SQLException {
        List<Candidate> candidates = findCandidates(connection, observedIdentity, now);
        if (candidates.isEmpty()) {
            return TemplateUpdatePrepareResult.noPendingWork();
        }
        Candidate candidate = candidates.getFirst();
        String mismatch = mismatchDetail(candidate, observedIdentity, candidates.size());
        if (mismatch != null) {
            boolean reviewed = movePendingToReview(
                    connection, candidate.mutationId(), mismatch, observedIdentity, now);
            return reviewed
                    ? TemplateUpdatePrepareResult.reviewRequired(mismatch)
                    : TemplateUpdatePrepareResult.noPendingWork();
        }

        long expiresAt = Math.addExact(now, leaseMillis);
        if (!claimCandidate(connection, candidate.mutationId(), claimToken, expiresAt, now)) {
            return TemplateUpdatePrepareResult.noPendingWork();
        }
        LoreItemIdentity targetIdentity = new LoreItemIdentity(
                observedIdentity.definitionId(),
                observedIdentity.instanceId(),
                new TemplateRevision(candidate.mutationDesiredRevision()));
        return TemplateUpdatePrepareResult.prepared(new PreparedTemplateUpdate(
                candidate.mutationId(),
                claimToken,
                observedIdentity,
                targetIdentity,
                new EncodedItemTemplate(candidate.codecVersion(), candidate.templateBlob()),
                expiresAt));
    }

    private static List<Candidate> findCandidates(
            Connection connection,
            LoreItemIdentity observedIdentity,
            long now) throws SQLException {
        List<Candidate> candidates = new ArrayList<>(2);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pm.mutation_id, pm.desired_revision AS mutation_desired_revision, "
                        + "li.applied_revision AS instance_applied_revision, "
                        + "li.desired_revision AS instance_desired_revision, "
                        + "li.lifecycle_state, current.state AS current_state, "
                        + "revision.codec_version, revision.template_blob "
                        + "FROM pending_mutations pm "
                        + "JOIN lore_instances li ON li.instance_id = pm.instance_id "
                        + "AND li.definition_id = pm.definition_id "
                        + "JOIN lore_definition_revisions revision "
                        + "ON revision.definition_id = pm.definition_id "
                        + "AND revision.revision = pm.desired_revision "
                        + "LEFT JOIN instance_current_state current "
                        + "ON current.instance_id = pm.instance_id "
                        + "WHERE pm.mutation_type = 'TEMPLATE_UPDATE' "
                        + "AND pm.definition_id = ? AND pm.instance_id = ? "
                        + "AND pm.state = 'PENDING' "
                        + "AND (pm.next_attempt_at IS NULL OR pm.next_attempt_at <= ?) "
                        + "ORDER BY pm.created_at, pm.mutation_id LIMIT 2")) {
            statement.setString(1, observedIdentity.definitionId().value().toString());
            statement.setString(2, observedIdentity.instanceId().value().toString());
            statement.setLong(3, now);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    candidates.add(readCandidate(resultSet));
                }
            }
        }
        return candidates;
    }

    private static Candidate readCandidate(ResultSet resultSet) throws SQLException {
        return new Candidate(
                UUID.fromString(resultSet.getString("mutation_id")),
                resultSet.getLong("mutation_desired_revision"),
                resultSet.getLong("instance_applied_revision"),
                resultSet.getLong("instance_desired_revision"),
                resultSet.getString("lifecycle_state"),
                resultSet.getString("current_state"),
                resultSet.getInt("codec_version"),
                resultSet.getBytes("template_blob"));
    }

    private static String mismatchDetail(
            Candidate candidate,
            LoreItemIdentity observedIdentity,
            int candidateCount) {
        long observedRevision = observedIdentity.appliedRevision().value();
        if (candidateCount > 1) {
            return "Multiple due template-update mutations exist for one encountered instance.";
        }
        if (!"ACTIVE".equals(candidate.lifecycleState())) {
            return "Template-update work targeted a non-active lore instance.";
        }
        if (candidate.mutationDesiredRevision() != candidate.instanceDesiredRevision()) {
            return "Mutation desired revision does not match the instance desired revision.";
        }
        if ("CONFLICTING".equals(candidate.currentState())) {
            return "The encountered lore instance has conflicting physical-location evidence.";
        }
        if (observedRevision != candidate.instanceAppliedRevision()
                && observedRevision != candidate.instanceDesiredRevision()) {
            return "Observed physical revision " + observedRevision
                    + " does not match database applied revision "
                    + candidate.instanceAppliedRevision()
                    + " or desired revision " + candidate.instanceDesiredRevision() + '.';
        }
        if (observedRevision > candidate.instanceDesiredRevision()) {
            return "Observed physical revision is newer than the queued desired revision.";
        }
        return null;
    }

    private static boolean claimCandidate(
            Connection connection,
            UUID mutationId,
            String claimToken,
            long expiresAt,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE pending_mutations SET state = 'CLAIMED', claim_token = ?, "
                        + "claim_expires_at = ?, attempt_count = attempt_count + 1, "
                        + "updated_at = ? WHERE mutation_id = ? "
                        + "AND mutation_type = 'TEMPLATE_UPDATE' AND state = 'PENDING' "
                        + "AND (next_attempt_at IS NULL OR next_attempt_at <= ?)")) {
            statement.setString(1, claimToken);
            statement.setLong(2, expiresAt);
            statement.setLong(3, now);
            statement.setString(4, mutationId.toString());
            statement.setLong(5, now);
            return statement.executeUpdate() == SINGLE_UPDATED_ROW;
        }
    }

    private static boolean movePendingToReview(
            Connection connection,
            UUID mutationId,
            String reason,
            LoreItemIdentity observedIdentity,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE pending_mutations SET state = 'REVIEW_REQUIRED', "
                        + "claim_token = NULL, claim_expires_at = NULL, updated_at = ? "
                        + "WHERE mutation_id = ? AND mutation_type = 'TEMPLATE_UPDATE' "
                        + "AND state = 'PENDING'")) {
            statement.setLong(1, now);
            statement.setString(2, mutationId.toString());
            if (statement.executeUpdate() != SINGLE_UPDATED_ROW) {
                return false;
            }
        }
        SQLiteTemplateUpdateAudit.appendPreparationReview(
                connection, mutationId, reason, observedIdentity, now);
        return true;
    }

    private record Candidate(
            UUID mutationId,
            long mutationDesiredRevision,
            long instanceAppliedRevision,
            long instanceDesiredRevision,
            String lifecycleState,
            String currentState,
            int codecVersion,
            byte[] templateBlob) {
        private Candidate {
            Objects.requireNonNull(mutationId, "mutationId");
            Objects.requireNonNull(lifecycleState, "lifecycleState");
            Objects.requireNonNull(templateBlob, "templateBlob");
            templateBlob = templateBlob.clone();
        }

        @Override
        public byte[] templateBlob() {
            return templateBlob.clone();
        }
    }
}
