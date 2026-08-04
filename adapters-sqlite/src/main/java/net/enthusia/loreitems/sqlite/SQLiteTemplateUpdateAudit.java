package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.PreparedTemplateUpdate;

final class SQLiteTemplateUpdateAudit {
    private static final int FIRST_CONTROL_CHARACTER = 0x20;
    private static final String MUTATION_AGGREGATE = "pending_mutation";
    private static final String INSTANCE_AGGREGATE = "lore_instance";
    private static final String SYSTEM_ACTOR = "system";

    private SQLiteTemplateUpdateAudit() {
    }

    static void appendPreparationReview(
            Connection connection,
            UUID mutationId,
            String reason,
            LoreItemIdentity observedIdentity,
            long now) throws SQLException {
        append(
                connection,
                MUTATION_AGGREGATE,
                mutationId.toString(),
                "template_update_review_required",
                prepareReviewJson(reason, observedIdentity),
                now);
    }

    static void appendRelease(
            Connection connection,
            PreparedTemplateUpdate update,
            String reason,
            long now) throws SQLException {
        append(
                connection,
                MUTATION_AGGREGATE,
                update.mutationId().toString(),
                "template_update_released",
                reasonJson(reason),
                now);
    }

    static void appendCompletion(
            Connection connection,
            PreparedTemplateUpdate update,
            String beforeFingerprint,
            String afterFingerprint,
            long now) throws SQLException {
        append(
                connection,
                INSTANCE_AGGREGATE,
                update.targetIdentity().instanceId().value().toString(),
                "template_update_completed",
                completionJson(update, beforeFingerprint, afterFingerprint),
                now);
    }

    static void appendClaimedReview(
            Connection connection,
            PreparedTemplateUpdate update,
            String reason,
            String beforeFingerprint,
            String afterFingerprint,
            long now) throws SQLException {
        append(
                connection,
                MUTATION_AGGREGATE,
                update.mutationId().toString(),
                "template_update_review_required",
                reviewJson(reason, beforeFingerprint, afterFingerprint),
                now);
    }

    private static void append(
            Connection connection,
            String aggregateType,
            String aggregateId,
            String eventType,
            String detailJson,
            long now) throws SQLException {
        SQLiteAuditRepository.appendInTransaction(
                connection,
                AuditEventRecord.pending(
                        aggregateType,
                        aggregateId,
                        eventType,
                        SYSTEM_ACTOR,
                        null,
                        detailJson,
                        now));
    }

    private static String prepareReviewJson(
            String reason,
            LoreItemIdentity observedIdentity) {
        return "{\"reason\":\"" + escapeJson(reason)
                + "\",\"observedRevision\":"
                + observedIdentity.appliedRevision().value() + '}';
    }

    private static String reasonJson(String reason) {
        return "{\"reason\":\"" + escapeJson(reason) + "\"}";
    }

    private static String completionJson(
            PreparedTemplateUpdate update,
            String beforeFingerprint,
            String afterFingerprint) {
        return "{\"mutationId\":\"" + update.mutationId()
                + "\",\"fromRevision\":"
                + update.observedIdentity().appliedRevision().value()
                + ",\"toRevision\":"
                + update.targetIdentity().appliedRevision().value()
                + ",\"beforeFingerprint\":\"" + escapeJson(beforeFingerprint)
                + "\",\"afterFingerprint\":\"" + escapeJson(afterFingerprint) + "\"}";
    }

    private static String reviewJson(
            String reason,
            String beforeFingerprint,
            String afterFingerprint) {
        StringBuilder json = new StringBuilder("{\"reason\":\"")
                .append(escapeJson(reason)).append('"');
        appendOptionalJson(json, "beforeFingerprint", beforeFingerprint);
        appendOptionalJson(json, "afterFingerprint", afterFingerprint);
        return json.append('}').toString();
    }

    private static void appendOptionalJson(
            StringBuilder json,
            String name,
            String value) {
        if (value != null) {
            json.append(",\"").append(name).append("\":\"")
                    .append(escapeJson(value)).append('"');
        }
    }

    private static String escapeJson(String value) {
        Objects.requireNonNull(value, "value");
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            appendEscaped(escaped, value.charAt(index));
        }
        return escaped.toString();
    }

    private static void appendEscaped(StringBuilder escaped, char character) {
        switch (character) {
            case '"' -> escaped.append("\\\"");
            case '\\' -> escaped.append("\\\\");
            case '\b' -> escaped.append("\\b");
            case '\f' -> escaped.append("\\f");
            case '\n' -> escaped.append("\\n");
            case '\r' -> escaped.append("\\r");
            case '\t' -> escaped.append("\\t");
            default -> appendLiteralOrControl(escaped, character);
        }
    }

    private static void appendLiteralOrControl(StringBuilder escaped, char character) {
        if (character < FIRST_CONTROL_CHARACTER) {
            escaped.append("\\u")
                    .append(Character.forDigit((character >>> 12) & 0xF, 16))
                    .append(Character.forDigit((character >>> 8) & 0xF, 16))
                    .append(Character.forDigit((character >>> 4) & 0xF, 16))
                    .append(Character.forDigit(character & 0xF, 16));
        } else {
            escaped.append(character);
        }
    }
}
