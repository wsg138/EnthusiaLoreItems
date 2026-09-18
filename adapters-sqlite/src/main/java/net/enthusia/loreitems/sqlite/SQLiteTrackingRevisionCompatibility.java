package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import net.enthusia.loreitems.application.TrackingObservationUseCase;

/** Determines whether observed template revision evidence is compatible with durable recovery state. */
final class SQLiteTrackingRevisionCompatibility {
    private SQLiteTrackingRevisionCompatibility() {}

    static boolean matches(
            Connection connection,
            String definitionId,
            long appliedRevision,
            long desiredRevision,
            TrackingObservationUseCase.Request request) throws SQLException {
        if (!definitionId.equals(request.identity().definitionId().value().toString())) {
            return false;
        }
        long observedRevision = request.identity().appliedRevision().value();
        if (appliedRevision == observedRevision) {
            return true;
        }
        return desiredRevision == observedRevision
                && hasRecoverableTemplateUpdate(
                        connection, request, definitionId, observedRevision);
    }

    private static boolean hasRecoverableTemplateUpdate(
            Connection connection,
            TrackingObservationUseCase.Request request,
            String definitionId,
            long desiredRevision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM pending_mutations WHERE mutation_type = 'TEMPLATE_UPDATE' "
                        + "AND definition_id = ? AND instance_id = ? AND desired_revision = ? "
                        + "AND state IN ('PENDING', 'CLAIMED', 'REVIEW_REQUIRED') LIMIT 1")) {
            statement.setString(1, definitionId);
            statement.setString(2, request.identity().instanceId().value().toString());
            statement.setLong(3, desiredRevision);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }
}
