package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;

/** Persists identity-mismatch anomaly detail without bloating the observation transaction flow. */
final class SQLiteTrackingIdentityMismatchSupport {
    private SQLiteTrackingIdentityMismatchSupport() {}

    static void upsertIdentityMismatchAnomaly(
            Connection connection,
            TrackingObservationUseCase.Request request,
            String durableDefinitionId,
            long durableAppliedRevision,
            LocationDescriptor currentLocation,
            long observedAt) throws SQLException {
        String detail = identityMismatchDetail(
                request,
                durableDefinitionId,
                durableAppliedRevision,
                currentLocation);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO instance_anomalies(anomaly_id, instance_id, definition_id, "
                        + "anomaly_type, status, detail, first_seen_at, last_seen_at, "
                        + "acknowledged_at, acknowledged_by, resolved_at, resolution_detail, "
                        + "state_revision) VALUES (?, ?, ?, 'IDENTITY_MISMATCH', 'OPEN', ?, ?, ?, "
                        + "NULL, NULL, NULL, NULL, 0) ON CONFLICT DO NOTHING")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, request.identity().instanceId().value().toString());
            statement.setString(3, durableDefinitionId);
            statement.setString(4, detail);
            statement.setLong(5, observedAt);
            statement.setLong(6, observedAt);
            if (statement.executeUpdate() == 0) {
                refreshIdentityMismatchAnomaly(
                        connection,
                        request,
                        durableDefinitionId,
                        detail,
                        observedAt);
            }
        }
    }

    private static void refreshIdentityMismatchAnomaly(
            Connection connection,
            TrackingObservationUseCase.Request request,
            String durableDefinitionId,
            String detail,
            long observedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE instance_anomalies SET detail = ?, last_seen_at = ?, "
                        + "state_revision = state_revision + 1 WHERE instance_id = ? "
                        + "AND definition_id = ? AND anomaly_type = 'IDENTITY_MISMATCH' "
                        + "AND status IN ('OPEN', 'ACKNOWLEDGED') AND last_seen_at <= ?")) {
            statement.setString(1, detail);
            statement.setLong(2, observedAt);
            statement.setString(3, request.identity().instanceId().value().toString());
            statement.setString(4, durableDefinitionId);
            statement.setLong(5, observedAt);
            statement.executeUpdate();
        }
    }

    private static String identityMismatchDetail(
            TrackingObservationUseCase.Request request,
            String durableDefinitionId,
            long durableAppliedRevision,
            LocationDescriptor currentLocation) {
        String previous = currentLocation == null
                ? "none"
                : currentLocation.type().name() + ':' + currentLocation.locationKey()
                        + (currentLocation.containerPath() == null
                                ? ""
                                : ':' + currentLocation.containerPath());
        LocationDescriptor observed = request.location();
        String observedLocation = observed.type().name() + ':' + observed.locationKey()
                + (observed.containerPath() == null ? "" : ':' + observed.containerPath());
        return "Identity mismatch observed at " + observedLocation
                + "; previous durable location=" + previous
                + "; durable definition=" + durableDefinitionId
                + " revision=" + durableAppliedRevision
                + "; observed definition="
                + request.identity().definitionId().value()
                + " revision=" + request.identity().appliedRevision().value()
                + ". Physical evidence was preserved and current state was fenced.";
    }
}
