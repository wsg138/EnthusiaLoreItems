package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.LocationDescriptor;

/**
 * INACTIVE, disposable-SQLite-only rehearsal of issue #103 CAS + append.
 * Never register this class with SQLiteStorageRuntime, Paper or startup.
 * The required rehearsal tables DO NOT exist in the application schema.
 * The fixture-only fence does not attest actual Paper or world lineage.
 */
public final class SQLitePhysicalProofReconciliationWriter {
    private static final String MARKER = "LOREITEMS_DISPOSABLE_REHEARSAL_ONLY_V1";
    private static final String SOURCE = "physical_proof_DISPOSABLE_REHEARSAL";
    private SQLitePhysicalProofReconciliationWriter() {}

    public enum Status { APPLIED, ALREADY_APPLIED, REFUSED }
    public enum Failpoint { NONE, AFTER_OBSERVATION, AFTER_CAS, AFTER_RECEIPT, AFTER_AUDIT }
    public record Result(Status status, String reason, long observationId) {
        static Result refuse(String reason) { return new Result(Status.REFUSED, reason, 0L); }
    }

    /** No outer/ambient transaction is allowed: one connection, one bounded transaction. */
    public static Result rehearse(Connection connection,
            SQLitePhysicalProofReconciliationPlanner.CurrentEvidence expected,
            SQLitePhysicalProofReconciliationPlanner.PhysicalProof proof) throws SQLException {
        return rehearse(connection, expected, proof, Failpoint.NONE);
    }

    static Result rehearse(Connection connection,
            SQLitePhysicalProofReconciliationPlanner.CurrentEvidence expected,
            SQLitePhysicalProofReconciliationPlanner.PhysicalProof proof,
            Failpoint failpoint) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(proof, "proof");
        Objects.requireNonNull(failpoint, "failpoint");
        if (!connection.getAutoCommit()) {
            throw new IllegalArgumentException("An independently owned transaction is required");
        }
        // Missing marker/fence tables are a hard error, never an implicit migration.
        verifyDisposableGuard(connection);
        connection.setAutoCommit(false);
        try {
            Result result = perform(connection, expected, proof, failpoint);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            try { connection.rollback(); } catch (SQLException error) { exception.addSuppressed(error); }
            throw exception;
        } finally {
            // If commit itself had an unknown outcome, callers must reopen/read receipt
            // rather than blindly interpreting the exception as an unapplied mutation.
            connection.setAutoCommit(true);
        }
    }

    private static void verifyDisposableGuard(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM reconciliation_rehearsal_guard WHERE marker = ?")) {
            statement.setString(1, MARKER);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) { throw new SQLException("Disposable rehearsal marker missing"); }
            }
        }
    }

    private static Result perform(Connection connection,
            SQLitePhysicalProofReconciliationPlanner.CurrentEvidence expected,
            SQLitePhysicalProofReconciliationPlanner.PhysicalProof proof,
            Failpoint failpoint) throws SQLException {
        if (!expected.instanceId().equals(proof.pdcInstanceId())) {
            return Result.refuse("PDC instance mismatch");
        }
        Result replay = replay(connection, proof);
        if (replay != null) { return replay; }
        SQLitePhysicalProofReconciliationPlanner.CurrentEvidence observed = readCurrent(connection, expected.instanceId());
        if (!expected.equals(observed)) { return Result.refuse("Current projection or fence changed"); }
        SQLitePhysicalProofReconciliationPlanner.Decision decision =
                SQLitePhysicalProofReconciliationPlanner.plan(observed, proof);
        if (decision.outcome() != SQLitePhysicalProofReconciliationPlanner.Outcome.ELIGIBLE) {
            return Result.refuse(decision.outcome() + ":" + decision.reason());
        }
        long id = appendObservation(connection, proof, decision);
        interrupt(failpoint, Failpoint.AFTER_OBSERVATION);
        if (!updateProjection(connection, observed, proof, decision, id)) {
            throw new SQLException("CAS rejected after append; rolled back");
        }
        interrupt(failpoint, Failpoint.AFTER_CAS);
        insertReceipt(connection, observed, proof, decision, id);
        interrupt(failpoint, Failpoint.AFTER_RECEIPT);
        appendAudit(connection, proof, id);
        interrupt(failpoint, Failpoint.AFTER_AUDIT);
        return new Result(Status.APPLIED, "disposable rehearsal only", id);
    }

    private static Result replay(Connection connection,
            SQLitePhysicalProofReconciliationPlanner.PhysicalProof proof) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT new_observation_id, location_key, container_path, captured_at "
              + "FROM reconciliation_rehearsal_receipts WHERE instance_id = ? "
              + "AND source_observation_id = ? AND generation = ?")) {
            query.setString(1, proof.pdcInstanceId().toString());
            query.setLong(2, proof.sourceObservationId());
            query.setLong(3, proof.eventGeneration());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) { return null; }
                long receipt = rows.getLong(1);
                SQLitePhysicalProofReconciliationPlanner.CurrentEvidence prior =
                        readProjectedSource(connection, proof.pdcInstanceId(), proof.sourceObservationId());
                SQLitePhysicalProofReconciliationPlanner.Decision decision =
                        SQLitePhysicalProofReconciliationPlanner.plan(prior, proof);
                if (decision.outcome() != SQLitePhysicalProofReconciliationPlanner.Outcome.ELIGIBLE
                        || !decision.proposedLocationKey().equals(rows.getString(2))
                        || !Objects.equals(decision.proposedContainerPath(), rows.getString(3))
                        || proof.capturedAtMillis() != rows.getLong(4)
                        || !receiptMatches(connection, proof.pdcInstanceId(), receipt, decision)) {
                    return Result.refuse("Replay payload or immutable observation mismatch");
                }
                return new Result(Status.ALREADY_APPLIED, "historical receipt only, not current location", receipt);
            }
        }
    }

    private static SQLitePhysicalProofReconciliationPlanner.CurrentEvidence readProjectedSource(
            Connection connection, UUID instanceId, long observationId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT o.location_type, o.location_key, o.container_path, o.confidence, o.observed_at, "
              + "r.old_state, r.old_revision, r.old_updated_at, r.generation "
              + "FROM instance_observations o JOIN reconciliation_rehearsal_receipts r "
              + "ON r.instance_id = o.instance_id AND r.source_observation_id = o.observation_id "
              + "WHERE o.instance_id = ? AND o.observation_id = ?")) {
            query.setString(1, instanceId.toString());
            query.setLong(2, observationId);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) { return null; }
                LocationDescriptor location = new LocationDescriptor(
                        LocationDescriptor.Type.valueOf(row.getString(1)), row.getString(2), row.getString(3));
                return new SQLitePhysicalProofReconciliationPlanner.CurrentEvidence(instanceId, location,
                        InstanceCurrentState.State.valueOf(row.getString(6)), row.getLong(7),
                        observationId, row.getLong(8), row.getLong(9), false);
            }
        }
    }

    private static boolean receiptMatches(Connection connection, UUID instanceId, long id,
            SQLitePhysicalProofReconciliationPlanner.Decision decision) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM instance_observations WHERE observation_id = ? AND instance_id = ? "
              + "AND location_key = ? AND container_path = ? AND confidence = 'LAST_CONFIRMED' "
              + "AND source = ?")) {
            query.setLong(1, id);
            query.setString(2, instanceId.toString());
            query.setString(3, decision.proposedLocationKey());
            query.setString(4, decision.proposedContainerPath());
            query.setString(5, SOURCE);
            try (ResultSet row = query.executeQuery()) { return row.next(); }
        }
    }

    private static SQLitePhysicalProofReconciliationPlanner.CurrentEvidence readCurrent(
            Connection connection, UUID instanceId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT c.state, c.location_type, c.location_key, c.container_path, "
              + "c.last_observation_id, c.state_revision, c.updated_at, f.generation, "
              + "f.dirty, f.ack_durable, f.source_observation_id, o.observed_at, o.confidence, "
              + "o.location_type, o.location_key, o.container_path "
              + "FROM instance_current_state c JOIN reconciliation_rehearsal_fence f "
              + "ON c.instance_id = f.instance_id JOIN instance_observations o "
              + "ON o.observation_id = c.last_observation_id AND o.instance_id = c.instance_id "
              + "WHERE c.instance_id = ?")) {
            query.setString(1, instanceId.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) { return null; }
                if (row.getInt(9) != 0 || row.getInt(10) != 1
                        || row.getLong(11) != row.getLong(5)
                        || row.getLong(12) > row.getLong(7)
                        || !compatibleConfidence(row.getString(1), row.getString(13))
                        || !Objects.equals(row.getString(2), row.getString(14))
                        || !Objects.equals(row.getString(3), row.getString(15))
                        || !Objects.equals(row.getString(4), row.getString(16))) { return null; }
                LocationDescriptor location = new LocationDescriptor(
                        LocationDescriptor.Type.valueOf(row.getString(2)), row.getString(3), row.getString(4));
                boolean conflict = hasConflict(connection, instanceId);
                return new SQLitePhysicalProofReconciliationPlanner.CurrentEvidence(instanceId, location,
                        InstanceCurrentState.State.valueOf(row.getString(1)), row.getLong(6),
                        row.getLong(5), row.getLong(7), row.getLong(8), conflict);
            }
        }
    }

    private static boolean compatibleConfidence(String state, String confidence) {
        return state.equals(confidence)
                || ("LAST_CONFIRMED".equals(state) && "CONFIRMED_NOW".equals(confidence));
    }

    private static boolean hasConflict(Connection connection, UUID instanceId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM instance_anomalies WHERE instance_id = ? AND status <> 'RESOLVED' LIMIT 1")) {
            query.setString(1, instanceId.toString());
            try (ResultSet row = query.executeQuery()) { return row.next(); }
        }
    }

    private static long appendObservation(Connection connection,
            SQLitePhysicalProofReconciliationPlanner.PhysicalProof proof,
            SQLitePhysicalProofReconciliationPlanner.Decision decision) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO instance_observations(instance_id, definition_id, location_type, "
              + "location_key, container_path, confidence, source, observed_at) "
              + "SELECT instance_id, definition_id, ?, ?, ?, 'LAST_CONFIRMED', ?, ? "
              + "FROM lore_instances WHERE instance_id = ? AND lifecycle_state = 'ACTIVE'",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, decision.proposedLocationKey().startsWith("root:")
                    ? "NESTED_CONTAINER" : "BLOCK_CONTAINER");
            insert.setString(2, decision.proposedLocationKey());
            insert.setString(3, decision.proposedContainerPath());
            insert.setString(4, SOURCE);
            insert.setLong(5, proof.capturedAtMillis());
            insert.setString(6, proof.pdcInstanceId().toString());
            if (insert.executeUpdate() != 1) { throw new SQLException("Instance is not active"); }
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (!keys.next()) { throw new SQLException("Missing durable observation ID"); }
                return keys.getLong(1);
            }
        }
    }

    private static boolean updateProjection(Connection connection,
            SQLitePhysicalProofReconciliationPlanner.CurrentEvidence old,
            SQLitePhysicalProofReconciliationPlanner.PhysicalProof proof,
            SQLitePhysicalProofReconciliationPlanner.Decision proposed, long id) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE instance_current_state SET state = 'LAST_CONFIRMED', location_type = ?, "
              + "location_key = ?, container_path = ?, last_observation_id = ?, "
              + "state_revision = state_revision + 1, updated_at = ? "
              + "WHERE instance_id = ? AND state = ? AND state_revision = ? "
              + "AND last_observation_id = ? AND updated_at = ? AND location_type = ? "
              + "AND location_key = ? AND container_path = ? "
              + "AND EXISTS (SELECT 1 FROM reconciliation_rehearsal_fence f "
              + "WHERE f.instance_id = instance_current_state.instance_id AND f.generation = ? "
              + "AND f.source_observation_id = ? AND f.dirty = 0 AND f.ack_durable = 1) "
              + "AND NOT EXISTS (SELECT 1 FROM instance_anomalies a WHERE a.instance_id = ? "
              + "AND a.status <> 'RESOLVED')")) {
            LocationDescriptor location = old.location();
            update.setString(1, proposed.proposedLocationKey().startsWith("root:")
                    ? "NESTED_CONTAINER" : "BLOCK_CONTAINER");
            update.setString(2, proposed.proposedLocationKey());
            update.setString(3, proposed.proposedContainerPath());
            update.setLong(4, id);
            update.setLong(5, proof.capturedAtMillis());
            update.setString(6, old.instanceId().toString());
            update.setString(7, old.state().name());
            update.setLong(8, old.revision());
            update.setLong(9, old.lastObservationId());
            update.setLong(10, old.updatedAtMillis());
            update.setString(11, location.type().name());
            update.setString(12, location.locationKey());
            update.setString(13, location.containerPath());
            update.setLong(14, proof.eventGeneration());
            update.setLong(15, proof.sourceObservationId());
            update.setString(16, old.instanceId().toString());
            return update.executeUpdate() == 1;
        }
    }

    private static void insertReceipt(Connection connection,
            SQLitePhysicalProofReconciliationPlanner.CurrentEvidence old,
            SQLitePhysicalProofReconciliationPlanner.PhysicalProof proof,
            SQLitePhysicalProofReconciliationPlanner.Decision decision, long id) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO reconciliation_rehearsal_receipts (instance_id, source_observation_id, "
              + "generation, new_observation_id, location_key, container_path, captured_at, "
              + "old_state, old_revision, old_updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, proof.pdcInstanceId().toString());
            insert.setLong(2, proof.sourceObservationId());
            insert.setLong(3, proof.eventGeneration());
            insert.setLong(4, id);
            insert.setString(5, decision.proposedLocationKey());
            insert.setString(6, decision.proposedContainerPath());
            insert.setLong(7, proof.capturedAtMillis());
            // Rehearsal never promotes CONFIRMED_NOW; preserve original projection in receipt.
            insert.setString(8, old.state().name());
            insert.setLong(9, old.revision());
            insert.setLong(10, old.updatedAtMillis());
            insert.executeUpdate();
        }
    }

    private static String originalState(Connection connection,
            SQLitePhysicalProofReconciliationPlanner.PhysicalProof proof) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement(
                "SELECT confidence FROM instance_observations WHERE observation_id = ? AND instance_id = ?")) {
            q.setLong(1, proof.sourceObservationId());
            q.setString(2, proof.pdcInstanceId().toString());
            try (ResultSet r = q.executeQuery()) {
                if (!r.next()) { throw new SQLException("Missing source receipt"); }
                // LAST_CONFIRMED projections may point at an original CONFIRMED_NOW receipt.
                return "LAST_CONFIRMED".equals(r.getString(1)) ? "LAST_CONFIRMED" : "CONFIRMED_NOW";
            }
        }
    }

    private static long sourceProjectionTimestamp(Connection connection,
            SQLitePhysicalProofReconciliationPlanner.PhysicalProof proof) throws SQLException {
        // Persist the exact caller snapshot, reconstructed from its capture freshness fence.
        // This lookup is fixture-scoped; stable source snapshot timestamp is saved in fence.
        try (PreparedStatement q = connection.prepareStatement(
                "SELECT old_updated_at FROM reconciliation_rehearsal_fence WHERE instance_id = ?")) {
            q.setString(1, proof.pdcInstanceId().toString());
            try (ResultSet r = q.executeQuery()) {
                if (!r.next()) { throw new SQLException("Missing fence timestamp"); }
                return r.getLong(1);
            }
        }
    }

    private static void appendAudit(Connection connection,
            SQLitePhysicalProofReconciliationPlanner.PhysicalProof proof, long id) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO audit_events(aggregate_type, aggregate_id, event_type, actor_type, "
              + "actor_id, detail_json, occurred_at) VALUES ('LORE_INSTANCE', ?, "
              + "'physical_reconciliation_DISPOSABLE_REHEARSAL', 'SYSTEM', NULL, ?, ?)")) {
            insert.setString(1, proof.pdcInstanceId().toString());
            insert.setString(2, "{\"sourceObservationId\":" + proof.sourceObservationId()
                    + ",\"newObservationId\":" + id + "}");
            insert.setLong(3, proof.capturedAtMillis());
            insert.executeUpdate();
        }
    }

    private static void interrupt(Failpoint active, Failpoint at) throws SQLException {
        if (active == at) { throw new SQLException("INJECTED REHEARSAL FAILURE " + at); }
    }
}
